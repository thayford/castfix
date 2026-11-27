package com.thayford.castfix;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/**
 * On Chromecast with Google TV and Google TV Streamer the Cast receiver gets its device credentials only from
 * the setup wizard (setupwraith's CastInfoProvider), and the wizard has to stay disabled for a replacement
 * launcher to own Home. The receiver keeps the credentials in memory until it restarts, so after each boot,
 * and each time CastWatch finds the receiver came back without them: switch the wizard on until the receiver
 * has loaded them, restarting the receiver if it doesn't take them by itself, then switch the wizard (and
 * the stock launcher, if it was off) off again.
 *
 * <p>On Google TV Streamer (kirkwood, UTTK.260317.003) a wizard left on is not harmless: system_server
 * crash-loops about every 47 s. So the wizard is on for as short a time as possible, every change is
 * recorded first (RunJournal), and a run that dies is put back by the next start.
 */
final class CastFixer {
    static final String SETUP = "com.google.android.tungsten.setupwraith";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    static final String CAST_RECEIVER = "com.google.android.apps.mediashell";
    private static final String CREDS_LOADED = "Created singleton instance of ClientAuthSigner";
    private static final String CREDS_MISSING = "Cannot get device credentials";
    private static final String WIZARD_ANSWERED = "querying cast infos";
    /** A receiver that can take the credentials does so within seconds once the wizard is on (PR #2). */
    private static final long SELF_LOAD_WAIT_MS = 20_000;
    /** A restarted receiver signed within 4 to 6 s on the HD. */
    private static final long RESTART_WAIT_MS = 60_000;
    /** The restarted receiver has to stay up: one that comes back after the wizard is off has nothing. */
    private static final long STABLE_MS = 10_000;
    /** How long a receiver may not answer on 8009 before its log decides instead. */
    private static final long NO_ANSWER_MS = 15_000;

    private final Context context;
    private final FixLog log;
    private final RunJournal journal;
    private String trigger = "unknown";

    CastFixer(Context context) {
        this.context = context;
        this.log = new FixLog(context);
        this.journal = new RunJournal(context);
    }

    /** True when a run left changes behind that the next run has to undo first. */
    boolean needsRecovery() {
        return journal.isActive();
    }

    String recoveryNote() {
        return journal.isActive()
                ? "Last run (" + journal.trigger() + ") did not finish; the next run puts things back first."
                : "";
    }

    /** Runs the whole fix and returns a one-line result for the screen. */
    String run(String trigger) {
        this.trigger = trigger;
        log.add("--- run (" + trigger + ")");
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return log.result("Needs WRITE_SECURE_SETTINGS, granted once from a computer (README, Setup).");
        }
        // Cheap checks first: while a previous run is unfinished the answer is already no, and probing would
        // block for up to the full handshake timeout before being discarded. The same condition also decides
        // whether a receiver that is signing counts as "already working": a wizard left on makes it not.
        if (!journal.isActive() && alreadyWorking(CastProbe.signed(), setupEnabled(context))) {
            return log.result("Casting already works.");
        }
        try {
            return log.result(SelfAdb.run(context, log, 90_000, this::session));
        } catch (SelfAdb.Unavailable e) {
            return log.result(e.getMessage());
        } catch (Exception e) {
            // The journal stays active on purpose: the next start puts things back.
            log.add("error: " + e);
            return log.result("Failed: " + e.getMessage());
        }
    }

    private String session(Shell shell) throws Exception {
        recover(shell);
        HomeState home = HomeState.read(shell);
        journal.begin(trigger, home.component);
        String result;
        try {
            result = fix(shell, home);
        } finally {
            // Whatever happened above, a run that had the wizard on switches it off again.
            if (journal.setupEnabledByUs()) disableWizard(shell);
        }
        journal.finish();
        return result;
    }

    private String fix(Shell shell, HomeState home) throws Exception {
        String installed = shell.run("pm list packages --user 0");
        if (!hasPackage(installed, SETUP)) return "This TV has no setup wizard (setupwraith); nothing to fix.";
        if (!hasPackage(installed, CAST_RECEIVER)) return "This TV has no Cast receiver (mediashell); nothing to fix.";
        String disabled = shell.run("pm list packages -d --user 0");
        boolean stockHomeOff = hasPackage(disabled, STOCK_HOME);
        Boolean signed = CastProbe.signed();
        long since;
        if (!hasPackage(disabled, SETUP)) {
            // On, and not by us (recover() has put back our own). A stock setup keeps it on, and casts.
            if (!stockHomeOff && !home.ownedByReplacement()) {
                return "The setup wizard is on, so casting works without a fix.";
            }
            if (wizardOwnsWifi(shell)) {
                return "The setup wizard is on and owns the saved Wi-Fi network, so it stays on: forget the "
                        + "network and join it again (README).";
            }
            log.add("setup wizard was left on (Home is " + home + "); switching it off at the end of this run");
            journal.setupEnabled();
            if (signed == Boolean.TRUE) {
                disableWizard(shell);
                return CastProbe.signed() == Boolean.FALSE
                        ? "Setup wizard switched back off, but the receiver lost its credentials; run it again."
                        : "Setup wizard switched back off. Casting ready.";
            }
            since = deviceTime(shell);
        } else {
            if (signed == Boolean.TRUE) return "Casting already works.";
            log.add("receiver " + (signed == null ? "not answering" : "signs with an empty signature")
                    + "; Home is " + home);
            // Stock launcher first, so a Home press meanwhile lands on it rather than on the wizard's
            // RecoveryActivity (priority 1, below launcherx's 2).
            if (stockHomeOff) {
                journal.markStockHomeWasOff();
                if (!enablePackage(shell, STOCK_HOME)) log.add("carrying on without the stock launcher");
                // Enabling it sets off a burst of activity on a 1.4 GB HD, and the next adb command then
                // tended to lose libadb's wakeup race (a 20 s stall in each of three test runs).
                Thread.sleep(3_000);
            }
            since = deviceTime(shell);
            journal.setupEnabled();
            if (!enablePackage(shell, SETUP)) {
                disableWizard(shell);
                if (stockHomeOff) restoreHome(shell, home);
                return "Could not switch the setup wizard on; nothing changed.";
            }
            log.add("setup wizard on, waiting for the Cast receiver");
        }
        boolean loaded = awaitSigner(shell, since, SELF_LOAD_WAIT_MS) || restartReceiver(shell);
        // Shut the wizard's window as early as possible, before the rest of the run.
        disableWizard(shell);
        if (stockHomeOff) restoreHome(shell, home);
        // The receiver keeps the credentials in memory, so they have to hold with the wizard off again.
        if (loaded && CastProbe.signed() == Boolean.FALSE) {
            log.add("receiver lost the credentials once the wizard was off");
            loaded = false;
        }
        log.add(loaded ? "Cast credentials loaded" : "Cast credentials did not load");
        return loaded ? "Casting ready." : "Cast credentials did not load; run it again.";
    }

    /**
     * Waits for the receiver to sign, asking it directly (CastProbe) while it answers on 8009, and reading its
     * log, from {@code since} on, when it doesn't.
     */
    private boolean awaitSigner(Shell shell, long since, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        long noAnswerUntil = SystemClock.elapsedRealtime() + NO_ANSWER_MS;
        boolean answered = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(3_000);
            Boolean signed = CastProbe.signed();
            if (signed == Boolean.TRUE) {
                log.add("receiver signs");
                return true;
            }
            if (signed == Boolean.FALSE) answered = true;
            if (!answered && SystemClock.elapsedRealtime() > noAnswerUntil) break;
        }
        if (answered) {
            log.add("receiver still signs with an empty signature");
            return false;
        }
        log.add("receiver not answering on 8009; reading its log");
        return logSaysLoaded(shell, since, deadline);
    }

    /**
     * The receiver's log since {@code since} (device epoch seconds). Filtered by message text: the line
     * that proves success comes from the cast_shell tag, and a `-s 'a|b'` filterspec matches nothing at all
     * (PR #1 measured 0 lines against 2128), which is what the old wait used.
     */
    private boolean logSaysLoaded(Shell shell, long since, long deadline) throws Exception {
        long answeredAt = 0;
        do {
            String castLog = shell.run("logcat -d -v epoch -T '" + since + ".000' | grep -E '"
                    + CREDS_LOADED + "|" + CREDS_MISSING + "|" + WIZARD_ANSWERED + "'");
            if (castLog.contains(CREDS_LOADED)) return true;
            int query = castLog.lastIndexOf(WIZARD_ANSWERED);
            if (query >= 0 && answeredAt == 0) answeredAt = SystemClock.elapsedRealtime();
            // The wizard answered and no failure followed for a few seconds.
            if (query >= 0 && castLog.indexOf(CREDS_MISSING, query) < 0
                    && SystemClock.elapsedRealtime() - answeredAt >= 6_000) {
                return true;
            }
            Thread.sleep(3_000);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    /**
     * Restarts the receiver while the wizard is on. One that has run without its credentials can keep
     * signing empty even after the wizard answers its query (seen on the HD after 15 hours, and at boot);
     * restarted with the wizard on, it signed within 6 s (cleanyourtv #2615). `am crash` has the system bring
     * it back; `am force-stop` and starting it by hand gives one Android kills 30 s later. Only reached when
     * it isn't signing, so no cast that could work is interrupted. From PR #2 by lukasmeggle.
     */
    private boolean restartReceiver(Shell shell) throws Exception {
        String pid = firstLine(shell.run("pidof " + CAST_RECEIVER));
        log.add("receiver (pid " + pid + ") didn't take the credentials; restarting it");
        long since = deviceTime(shell);
        shell.run("am crash " + CAST_RECEIVER);
        if (!awaitSigner(shell, since, RESTART_WAIT_MS)) return false;
        String started = firstLine(shell.run("pidof " + CAST_RECEIVER));
        Thread.sleep(STABLE_MS);
        String later = firstLine(shell.run("pidof " + CAST_RECEIVER));
        if (!started.equals(later)) {
            log.add("receiver restarted again (pid " + started + " -> " + later + ")");
            return awaitSigner(shell, deviceTime(shell), RESTART_WAIT_MS);
        }
        return true;
    }

    /** Puts back what a run that died left behind: the wizard first, then Home. From PR #1 (svaldivia12). */
    private void recover(Shell shell) throws Exception {
        if (!journal.isActive()) return;
        log.add("previous run (" + journal.trigger() + ") did not finish; putting things back first");
        if (journal.setupEnabledByUs()) disableWizard(shell);
        if (journal.stockHomeWasOff()) restoreHome(shell, new HomeState(journal.homeBefore()));
        journal.finish();
        log.add("recovered");
    }

    /** Disables the wizard and confirms it; a silent failure here is what kirkwood's crash loop grows from. */
    private boolean disableWizard(Shell shell) throws Exception {
        Shell.Result result = shell.runChecked("pm disable-user --user 0 " + SETUP);
        // The package list is the authority here, not the exit status: a status that never came back says
        // nothing about the device, and abandoning the check on it would skip the one verification this
        // path exists for. The status is only used to explain the failure.
        if (!hasPackage(shell.run("pm list packages -d --user 0"), SETUP)) {
            log.add("setup wizard still enabled after disable-user"
                    + (result.ok() ? "" : " (" + result.problem() + ")"));
            return false;
        }
        journal.setupDisabled();
        log.add("setup wizard off");
        return true;
    }

    /** Enables a package and checks the result in the package list, the state that matters. */
    private boolean enablePackage(Shell shell, String pkg) throws Exception {
        String output = shell.run("pm enable --user 0 " + pkg);
        if (hasPackage(shell.run("pm list packages -d --user 0"), pkg)) {
            String text = output.trim();
            log.add("pm enable " + pkg + " failed: " + (text.isEmpty() ? "still disabled" : text.split("\n")[0]));
            return false;
        }
        return true;
    }

    /**
     * Puts the replacement launcher back as Home and switches the stock one off again, or leaves the stock
     * one on if that fails. Checked with the resolver afterwards (HomeState). The replacement is brought to
     * the front only if the stock launcher or the wizard took the screen, so a run while something is
     * playing doesn't interrupt it.
     */
    private void restoreHome(Shell shell, HomeState before) throws Exception {
        if (!before.ownedByReplacement()) {
            log.add("no replacement launcher owned Home; leaving the stock launcher on");
            return;
        }
        String front = shell.run("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'");
        if (front.contains(STOCK_HOME) || front.contains(SETUP)) shell.run("am start -n " + before.component);
        Shell.Result disabled = shell.runChecked("pm disable-user --user 0 " + STOCK_HOME);
        shell.run("cmd package set-home-activity --user 0 " + before.component);
        HomeState after = HomeState.read(shell);
        if (after.component.equals(before.component)) {
            // Home is right, but a stock launcher still enabled is the other half of what this run promises
            // to hand back, so it is verified rather than assumed from the command that was sent.
            if (!disabled.ok() && !hasPackage(shell.run("pm list packages -d --user 0"), STOCK_HOME)) {
                log.add("Home is " + before + " again, but the stock launcher stayed on (" + disabled.problem() + ")");
                return;
            }
            log.add("Home is " + before + " again");
            return;
        }
        enablePackage(shell, STOCK_HOME);
        log.add("Home went to " + after + "; stock launcher switched back on");
        if (after.ownedByWizard()) disableWizard(shell);
    }

    /**
     * Whether the wizard created a saved Wi-Fi network. Android deletes such a network when the wizard is
     * disabled, which takes a TV without Ethernet off the network (cleanyourtv's Chromecast lists).
     */
    private boolean wizardOwnsWifi(Shell shell) throws Exception {
        return shell.run("dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u").contains(SETUP);
    }

    private static long deviceTime(Shell shell) throws Exception {
        try {
            return Long.parseLong(shell.run("date +%s").trim());
        } catch (NumberFormatException e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    private static String firstLine(String text) {
        String trimmed = text.trim();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).trim();
    }

    private static boolean hasPackage(String packageList, String pkg) {
        for (String line : packageList.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    /**
     * Whether the setup wizard is enabled for this user, read from the package manager so it needs no adb.
     *
     * <p>Returns null rather than false when the package manager cannot answer: the two callers need
     * opposite defaults. The run shortcut treats "unknown" as on, so it never skips work it cannot rule out;
     * the screen treats "unknown" as nothing it can claim, which is different from claiming the wizard is
     * off. Collapsing the two is what let the screen call a risky TV healthy.
     */
    static Boolean setupEnabled(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(SETUP, 0);
            return info.applicationInfo != null && info.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            // No wizard on this device at all: nothing can be enabled, so "off" is the honest answer.
            return false;
        } catch (RuntimeException e) {
            // A visibility or permission failure says nothing about the package's state.
            return null;
        }
    }

    /**
     * Whether a run can skip its work because the TV is already as it should be.
     *
     * <p>Split out so the rule can be tested without Android, which matters because getting it wrong is
     * silent: the wrong answer is a plausible sentence on the screen, not a crash. Both conditions are
     * required, and "unknown" never qualifies.
     *
     * <p>This covers only the observable state. Whether the run is allowed to ask at all is decided by the
     * caller, which skips the probe entirely while a previous run is unfinished: that is a reason not to
     * ask rather than part of the verdict.
     *
     * @param signed        the receiver's answer: TRUE signed, FALSE unsigned, null no answer
     * @param wizardEnabled the setup wizard's state: TRUE on, FALSE off, null unreadable
     */
    static boolean alreadyWorking(Boolean signed, Boolean wizardEnabled) {
        return signed == Boolean.TRUE
                // Only a provable "off" qualifies. A wizard left on is the crash-loop state on kirkwood, and
                // an unreadable state is not evidence that it is off.
                && Boolean.FALSE.equals(wizardEnabled);
    }

    /**
     * True when a run failed in a way a later attempt could plausibly fix, which is what earns the single
     * retry after a boot run loses to the network.
     *
     * <p>Lives here, beside the messages it matches, so the rule and the strings cannot drift apart: matching
     * "no Wi-Fi" against SelfAdb's "No Wi-Fi after 90 s" once failed on case alone and silently disabled the
     * retry. Matching is case-insensitive for the same reason, and covers only transient causes.
     */
    static boolean transientFailure(String result) {
        if (result == null) return false;
        String text = result.toLowerCase(java.util.Locale.US);
        return text.contains("no wi-fi")
                || text.contains("wireless debugging")
                || text.contains("did not load");
    }
}
