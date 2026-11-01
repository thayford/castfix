package com.thayford.castfix;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.Inet4Address;

/**
 * On Chromecast with Google TV the Cast receiver gets its device credentials only from the setup wizard
 * (setupwraith's CastInfoProvider), and the wizard has to stay disabled for a replacement launcher to own
 * Home. The receiver keeps the credentials in memory once loaded, so after each boot: switch the wizard on
 * until the receiver has loaded them, then switch it and the stock launcher off again.
 *
 * <p>On Google TV Streamer (kirkwood, Google TV 14, UTTK.260317.003) leaving the wizard enabled is not a
 * neutral state: its RecoveryActivity claims Home and system_server crash-loops inside Task.moveToBack
 * roughly every 47 s, taking setupwraith and SystemUI down with it. So the wizard is enabled for the
 * shortest possible window and is put back on every exit path, including a killed process (see
 * {@link RunJournal}).
 */
final class CastFixer {
    static final String SETUP = "com.google.android.tungsten.setupwraith";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    static final String CAST_RECEIVER = "com.google.android.apps.mediashell";

    private static final String ADB_WIFI = "adb_wifi_enabled";
    private static final String ADB_TIMEOUT = "adb_allowed_connection_time";
    private static final String KEEP_DEBUGGING = "castfix_keep_debugging";
    /**
     * The receiver retries once a minute while loading fails and logs the signer when it succeeds; 100 s
     * leaves room for one missed retry.
     */
    private static final long CREDS_WAIT_MS = 100_000;

    private final Context context;
    private final FixLog log;
    private final RunJournal journal;

    CastFixer(Context context) {
        this.context = context;
        this.log = new FixLog(context);
        this.journal = new RunJournal(context);
    }

    /** Runs the whole fix and returns a one-line result for the screen. */
    String run(String trigger) {
        log.add("--- run (" + trigger + ")");
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return log.result("Needs WRITE_SECURE_SETTINGS, granted once from a computer (README, Setup).");
        }
        String ip = waitForWifi(90_000);
        if (ip == null) return log.result("No Wi-Fi after 90 s; nothing changed.");
        ContentResolver resolver = context.getContentResolver();
        boolean debuggingWasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1;
        Settings.Global.putInt(resolver, ADB_WIFI, 1);
        // Only recorded as ours when it was not already 0, so the uninstall path can put it back instead of
        // silently converting the owner's (and every other paired key's) expiry to "never".
        boolean timeoutWasSet = setAdbTimeoutOnce(resolver);
        try (Shell shell = new Shell(context, ip, log)) {
            if (!shell.connect()) return log.result("Could not reach Wireless debugging on this TV.");
            // Recovery before any new work: putting the device back matters more than fixing it again, and a
            // wizard left on by a dead run is the one state that must not survive another run. It also hands
            // Home back, so what is read afterwards is the launcher the owner actually chose.
            recover(shell);
            journal.begin(trigger, debuggingWasOn, timeoutWasSet, HomeState.read(shell).component);
            String result;
            try {
                result = fix(shell);
            } finally {
                // Last-resort guard: whatever happened above, a run that enabled the wizard disables it again.
                if (journal.setupEnabledByUs()) disableWizard(shell);
            }
            journal.finish();
            return log.result(result);
        } catch (Exception e) {
            // The journal stays active on purpose: the next start recovers.
            log.add("error: " + e);
            return log.result("Failed: " + e.getMessage());
        } finally {
            // Troubleshooting: `settings put global castfix_keep_debugging 1` keeps it on to read the logs.
            if (!debuggingWasOn && Settings.Global.getInt(resolver, KEEP_DEBUGGING, 0) != 1) {
                Settings.Global.putInt(resolver, ADB_WIFI, 0);
                log.add("Wireless debugging switched off again");
            }
        }
    }

    /** The fix proper; returns the one-line result. Never logs it, so the caller can attach the outcome. */
    private String fix(Shell shell) throws Exception {
        String disabled = shell.run("pm list packages -d --user 0");
        if (!packageExists(shell, SETUP)) {
            return "This TV has no setup wizard package, so there is nothing for Cast Fix to do.";
        }
        if (!packageExists(shell, CAST_RECEIVER)) {
            return "This TV has no Cast receiver (mediashell), so it is not a Google TV device.";
        }
        boolean setupOn = !hasPackage(disabled, SETUP);
        ReceiverState.Verdict verdict = ReceiverState.watch(shell, log, CREDS_WAIT_MS);

        if (verdict.healthy() && setupOn) {
            // Not "already fine": on kirkwood an enabled wizard is the crash-loop precondition. This is a
            // repair, and the message must read as one.
            log.add("receiver healthy (" + verdict + ") but the setup wizard was on; switching it off");
            disableWizard(shell);
            journal.finish();
            return "Setup wizard switched back off. Casting ready.";
        }
        if (verdict.healthy()) {
            log.add("receiver healthy (" + verdict + ")");
            journal.finish();
            return "Casting already works.";
        }

        String homeBefore = journal.homeBefore();
        long wizardOpenedAt = 0;
        if (setupOn) {
            // Somebody else left it on: it still has to come off when this run ends. The window starts now,
            // because whatever failed before this point is not this run's doing.
            log.add("setup wizard was already on; waiting for the receiver");
            journal.setupEnabled();
            wizardOpenedAt = ReceiverState.deviceNow(shell);
        } else {
            boolean stockHomeWasOff = hasPackage(disabled, STOCK_HOME) && packageExists(shell, STOCK_HOME);
            // Stock launcher first. Measured on kirkwood the resolver ranks launcherx (priority 2) above
            // setupwraith/.RecoveryActivity (priority 1), so with nothing else pinned this keeps a Home press
            // away from the wizard. Belt-and-braces, not a guarantee: where setupwraith's MainActivity is
            // enabled it outranks both at priority 4. The protection that holds is the wizard going off again
            // before this run ends.
            if (stockHomeWasOff) {
                journal.markStockHomeWasOff();
                if (enablePackage(shell, STOCK_HOME)) {
                    log.add("stock launcher on");
                } else {
                    log.add("could not enable the stock launcher; carrying on with the wizard alone");
                }
            }
            // Timestamped before the enable, so the wait can ignore the retry that failed just before it.
            long requestedAt = ReceiverState.deviceNow(shell);
            if (!enablePackage(shell, SETUP)) {
                restoreHome(shell, homeBefore);
                journal.finish();
                return "Could not switch the setup wizard on; nothing changed.";
            }
            log.add("setup wizard on, waiting for the Cast receiver");
            journal.setupEnabled();
            wizardOpenedAt = requestedAt;
        }

        long loadedAt = awaitSigner(shell, wizardOpenedAt);
        // Close the crash-loop window as early as possible, not after the rest of the run.
        disableWizard(shell);
        restoreHome(shell, homeBefore);
        journal.finish();
        if (loadedAt > 0) {
            log.add("credentials loaded");
            return "Casting ready (credentials loaded).";
        }
        return "Cast credentials did not load; run it again.";
    }

    /**
     * Waits for the receiver to pick up credentials after the wizard came on, and returns 1 on success, 0 on
     * failure.
     *
     * <p>Ignores anything logged before {@code enabledAt}: the retry that failed just before the wizard was
     * enabled is still in the buffer and would otherwise be read as "the wizard did not help". Uses the same
     * window logic as the health check rather than the signer line alone, because a receiver that has loaded
     * credentials and stopped retrying does not log the signer again (measured on kirkwood: signature 256 on
     * the 8009 handshake while no singleton line existed in any buffer).
     */
    private long awaitSigner(Shell shell, long enabledAt) throws Exception {
        ReceiverState.Verdict verdict = ReceiverState.watch(shell, log, CREDS_WAIT_MS, enabledAt);
        log.add("receiver after enabling the wizard: " + verdict);
        return verdict.healthy() ? 1 : 0;
    }

    /**
     * Puts the device back after a run that did not finish. Order is not negotiable: the wizard goes off
     * first, because leaving it on with the stock launcher on is the kirkwood crash-loop state.
     */
    private void recover(Shell shell) throws Exception {
        if (!journal.isActive()) return;
        log.add("previous run (" + journal.trigger() + ") did not finish; putting things back first");
        if (journal.setupEnabledByUs()
                || !hasPackage(shell.run("pm list packages -d --user 0"), SETUP)) {
            disableWizard(shell);
        }
        if (journal.stockHomeWasOff() && packageExists(shell, STOCK_HOME)) {
            if (!enablePackage(shell, STOCK_HOME)) log.add("could not re-enable the stock launcher");
        }
        String homeBefore = journal.homeBefore();
        if (!homeBefore.isEmpty() && new HomeState(homeBefore).ownedByReplacement()) {
            HomeState now = HomeState.read(shell);
            boolean homeIsWrong = !now.component.equals(homeBefore);
            // Restore the owner's launcher whenever Home is not already there, including when the stock
            // launcher got it: a run killed between enabling the stock launcher and restoring Home leaves
            // exactly that, and it is not the state to return to.
            if (homeIsWrong) {
                shell.run("am start -n " + homeBefore);
                shell.run("cmd package set-home-activity --user 0 " + homeBefore);
                HomeState after = HomeState.read(shell);
                if (after.component.equals(homeBefore)) {
                    log.add("Home restored to " + homeBefore);
                    // The stock launcher was only on for the fix; put it back as the owner had it.
                    if (journal.stockHomeWasOff()) {
                        shell.run("pm disable-user --user 0 " + STOCK_HOME);
                        log.add("stock launcher off again");
                    }
                } else {
                    log.add("Home went to " + (after.found() ? after.component : "(none)")
                            + "; leaving the stock launcher on");
                }
            }
        }
        if (journal.adbTimeoutWasSet()) {
            // Left set on purpose: the next run still needs it, and the uninstall path restores it.
            log.add("adb_allowed_connection_time was set by the unfinished run; uninstall restores it");
        }
        journal.finish();
        log.add("recovered");
    }

    /** Disables the wizard and confirms it; a silent failure here is what the crash loop grows out of. */
    private boolean disableWizard(Shell shell) throws Exception {
        Shell.Result result = shell.runChecked("pm disable-user --user 0 " + SETUP);
        if (!result.ok()) {
            log.add("could not disable the setup wizard: " + result.problem());
            return false;
        }
        if (!hasPackage(shell.run("pm list packages -d --user 0"), SETUP)) {
            log.add("setup wizard still enabled after disable-user");
            return false;
        }
        log.add("setup wizard off");
        return true;
    }

    private boolean enablePackage(Shell shell, String pkg) throws Exception {
        if (!packageExists(shell, pkg)) {
            log.add(pkg + " is not installed on this TV");
            return false;
        }
        Shell.Result result = shell.runChecked("pm enable --user 0 " + pkg);
        if (!result.ok()) {
            log.add("pm enable " + pkg + " failed: " + result.problem());
            return false;
        }
        return true;
    }

    /**
     * Puts the replacement launcher back as Home, or leaves the stock one on if that fails. Verified by asking
     * the resolver afterwards, not by reading window focus: on kirkwood `dumpsys window` prints
     * `mCurrentFocus=null`, so the old check never fired and the launcher was disabled without being brought
     * forward first.
     */
    private void restoreHome(Shell shell, String homeBefore) throws Exception {
        HomeState before = new HomeState(homeBefore);
        if (!before.ownedByReplacement()) {
            log.add("no replacement launcher owned Home; leaving the stock launcher on");
            return;
        }
        // Bring it up before disabling the stock one, so a Home press during the switch has somewhere to go.
        HomeState now = HomeState.read(shell);
        if (!now.component.equals(before.component)) shell.run("am start -n " + before.component);
        shell.run("pm disable-user --user 0 " + STOCK_HOME);
        shell.run("cmd package set-home-activity --user 0 " + before.component);
        HomeState after = HomeState.read(shell);
        if (after.component.equals(before.component)) {
            log.add("Home is " + before.component + " again");
            return;
        }
        log.add("Home went to " + (after.found() ? after.component : "(none)")
                + "; stock launcher switched back on");
        if (packageExists(shell, STOCK_HOME)) enablePackage(shell, STOCK_HOME);
        // "Not the replacement launcher" is not the only bad outcome: Home on the wizard is the crash loop.
        if (after.ownedByWizard()) disableWizard(shell);
    }

    /**
     * Turns off the 7-day adb key expiry, but only when it is not already off, so the uninstall path can tell
     * whether this app is the reason. Returns true if this run set it.
     */
    private boolean setAdbTimeoutOnce(ContentResolver resolver) {
        long current = Settings.Global.getLong(resolver, ADB_TIMEOUT, -1L);
        if (current == 0) return false;
        Settings.Global.putLong(resolver, ADB_TIMEOUT, 0L);
        return true;
    }

    private boolean packageExists(Shell shell, String pkg) throws Exception {
        return hasPackage(shell.run("pm list packages --user 0"), pkg);
    }

    /** The Wi-Fi IPv4 address once Wi-Fi is up, or null on timeout. */
    private String waitForWifi(long timeoutMs) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            LinkProperties link = network == null ? null : cm.getLinkProperties(network);
            if (caps != null && link != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                for (LinkAddress address : link.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
                }
            }
            SystemClock.sleep(1_000);
        }
        return null;
    }

    private static boolean hasPackage(String packageList, String pkg) {
        for (String line : packageList.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    /** True when a run left the device in a state it did not finish undoing. */
    boolean needsRecovery() {
        return journal.isActive();
    }

    /** A one-line description of the unfinished run, for the status screen. */
    String recoveryNote() {
        if (!journal.isActive()) return "";
        return "Last run (" + journal.trigger() + ") did not finish; the next run puts things back first.";
    }
}
