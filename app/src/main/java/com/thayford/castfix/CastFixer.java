package com.thayford.castfix;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/**
 * On Chromecast with Google TV the Cast receiver gets its device credentials only from the setup wizard
 * (setupwraith's CastInfoProvider), and the wizard has to stay disabled for a replacement launcher to own
 * Home. The receiver keeps the credentials in memory until it restarts, so after each boot, and each time
 * CastWatch finds the receiver came back without them: switch the wizard on until the receiver has loaded
 * them, then switch it and the stock launcher off again.
 */
final class CastFixer {
    static final String SETUP = "com.google.android.tungsten.setupwraith";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    private static final String CREDS_LOADED = "Created singleton instance of ClientAuthSigner";
    private static final String CREDS_MISSING = "Cannot get device credentials";
    private static final String WIZARD_ANSWERED = "querying cast infos";
    private static final String HOME_QUERY =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    /** The receiver retries loading every 60 s while it fails; leave room for one missed retry. */
    private static final long CREDS_WAIT_MS = 100_000;

    private final Context context;
    private final FixLog log;

    CastFixer(Context context) {
        this.context = context;
        this.log = new FixLog(context);
    }

    /** Runs the whole fix and returns a one-line result for the screen. */
    String run(String trigger) {
        log.add("--- run (" + trigger + ")");
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return log.result("Needs WRITE_SECURE_SETTINGS, granted once from a computer (README, Setup).");
        }
        if (CastProbe.signed() == Boolean.TRUE) return log.result("Casting already works.");
        try {
            return SelfAdb.run(context, log, 90_000, this::fix);
        } catch (SelfAdb.Unavailable e) {
            return log.result(e.getMessage());
        } catch (Exception e) {
            log.add("error: " + e);
            return log.result("Failed: " + e.getMessage());
        }
    }

    private String fix(Shell shell) throws Exception {
        String disabled = shell.run("pm list packages -d");
        if (!hasPackage(disabled, SETUP)) {
            return log.result("The setup wizard is on, so casting works without a fix.");
        }
        String home = lastLine(shell.run(HOME_QUERY));
        boolean stockHomeWasOff = hasPackage(disabled, STOCK_HOME);
        String since = shell.run("date '+%m-%d %H:%M:%S.000'").trim();
        log.add("Home is " + home);
        // Stock launcher first: a Home press in the next minute then lands on it, not on the wizard's
        // RecoveryActivity (seen crash-looping system_server on other Google TV 14 builds).
        if (stockHomeWasOff) shell.run("pm enable --user 0 " + STOCK_HOME);
        shell.run("pm enable --user 0 " + SETUP);
        log.add("setup wizard on, waiting for the Cast receiver");
        boolean loaded = false;
        long answeredAt = 0;
        long deadline = SystemClock.elapsedRealtime() + CREDS_WAIT_MS;
        while (!loaded && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(3_000);
            String castLog = shell.run("logcat -d -T '" + since + "' -s cast_shell:I"
                    + " cast_ClientAuthCredsFactoryProvisioned:E 'SetupWraith|CastInfoProvider:I'");
            int query = castLog.lastIndexOf(WIZARD_ANSWERED);
            if (query >= 0 && answeredAt == 0) answeredAt = SystemClock.elapsedRealtime();
            // Either the receiver says so, or the wizard answered it and no failure followed for a few seconds.
            loaded = castLog.contains(CREDS_LOADED) || (query >= 0
                    && castLog.indexOf(CREDS_MISSING, query) < 0
                    && SystemClock.elapsedRealtime() - answeredAt >= 6_000);
        }
        log.add(loaded ? "Cast credentials loaded" : "Cast credentials did not load in time");
        shell.run("pm disable-user --user 0 " + SETUP);
        if (stockHomeWasOff) restoreHome(shell, home);
        return log.result(loaded ? "Casting ready." : "Cast credentials did not load; run it again.");
    }

    /** Puts the replacement launcher back as Home, or leaves the stock one on if that fails. */
    private void restoreHome(Shell shell, String home) throws Exception {
        boolean replacement = home.contains("/") && !home.startsWith(STOCK_HOME + "/")
                && !home.startsWith(SETUP + "/") && !home.startsWith("android/");
        if (!replacement) {
            log.add("no replacement launcher owned Home; leaving the stock launcher on");
            return;
        }
        // Same order as cleanyourtv's build-and-install: start the new Home, disable the stock one,
        // set the preference last (Android drops it whenever the set of Home apps changes).
        String focus = shell.run("dumpsys window | grep mCurrentFocus");
        if (focus.contains(STOCK_HOME) || focus.contains(SETUP)) shell.run("am start -n " + home);
        shell.run("pm disable-user --user 0 " + STOCK_HOME);
        shell.run("cmd package set-home-activity --user 0 " + home);
        String now = lastLine(shell.run(HOME_QUERY));
        if (now.equals(home)) {
            log.add("Home is " + home + " again");
        } else {
            shell.run("pm enable --user 0 " + STOCK_HOME);
            log.add("Home went to " + now + "; stock launcher switched back on");
        }
    }

    private static boolean hasPackage(String packageList, String pkg) {
        for (String line : packageList.split("\n")) {
            if (line.trim().equals("package:" + pkg)) return true;
        }
        return false;
    }

    private static String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines[lines.length - 1].trim();
    }
}
