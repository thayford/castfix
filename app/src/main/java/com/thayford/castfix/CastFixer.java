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
 */
final class CastFixer {
    static final String SETUP = "com.google.android.tungsten.setupwraith";
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    private static final String CAST_RECEIVER = "com.google.android.apps.mediashell";
    private static final String CREDS_LOADED = "Created singleton instance of ClientAuthSigner";
    private static final String CREDS_MISSING = "Cannot get device credentials";
    private static final String WIZARD_ANSWERED = "querying cast infos";
    private static final String ADB_WIFI = "adb_wifi_enabled";
    private static final String HOME_QUERY =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    /** The receiver retries loading every 60 s while it fails. */
    private static final long RETRY_WINDOW_S = 75;
    /** Leave room for one missed retry. */
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
        String ip = waitForWifi(90_000);
        if (ip == null) return log.result("No Wi-Fi after 90 s; nothing changed.");
        ContentResolver resolver = context.getContentResolver();
        boolean debuggingWasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1;
        Settings.Global.putInt(resolver, ADB_WIFI, 1);
        // A paired key otherwise lapses after 7 days without a connection, and restarts can be further apart.
        Settings.Global.putLong(resolver, "adb_allowed_connection_time", 0L);
        try (Shell shell = new Shell(context, ip, log)) {
            if (!shell.connect()) return log.result("Could not reach Wireless debugging on this TV.");
            return fix(shell);
        } catch (Exception e) {
            log.add("error: " + e);
            return log.result("Failed: " + e.getMessage());
        } finally {
            // Troubleshooting: `settings put global castfix_keep_debugging 1` keeps it on to read the logs.
            if (!debuggingWasOn && Settings.Global.getInt(resolver, "castfix_keep_debugging", 0) != 1) {
                Settings.Global.putInt(resolver, ADB_WIFI, 0);
                log.add("Wireless debugging switched off again");
            }
        }
    }

    private String fix(Shell shell) throws Exception {
        String disabled = shell.run("pm list packages -d");
        if (!hasPackage(disabled, SETUP)) {
            return log.result("The setup wizard is on, so casting works without a fix.");
        }
        if (credsLoaded(shell)) return log.result("Casting already works.");
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

    /**
     * True when the Cast receiver already holds its credentials: either its latest credentials message says
     * they loaded, or it has been up longer than its retry interval without failing in that time. It retries
     * once a minute for as long as loading fails, and stops once they load.
     */
    private boolean credsLoaded(Shell shell) throws Exception {
        String castLog = shell.run("logcat -d -v epoch -s cast_shell:I cast_ClientAuthCredsFactoryProvisioned:E");
        if (castLog.lastIndexOf(CREDS_LOADED) > castLog.lastIndexOf(CREDS_MISSING)) return true;
        long upSeconds = elapsedSeconds(shell.run("ps -o etime= -p $(pidof " + CAST_RECEIVER + ")"));
        if (upSeconds < RETRY_WINDOW_S) return false;
        long now = Long.parseLong(shell.run("date +%s").trim());
        long lastFailure = 0;
        for (String line : castLog.split("\n")) {
            if (!line.contains(CREDS_MISSING)) continue;
            String[] fields = line.trim().split("\\s+");
            try {
                lastFailure = (long) Double.parseDouble(fields[0]);
            } catch (NumberFormatException ignored) {
                // not an epoch-stamped line
            }
        }
        log.add("Cast receiver up " + upSeconds + " s, last credentials failure "
                + (lastFailure == 0 ? "none in the log" : (now - lastFailure) + " s ago"));
        return now - lastFailure > RETRY_WINDOW_S;
    }

    /** Seconds from toybox `ps -o etime` ([[dd-]hh:]mm:ss), or 0 if it can't be read. */
    private static long elapsedSeconds(String etime) {
        String value = etime.trim();
        try {
            long days = 0;
            int dash = value.indexOf('-');
            if (dash > 0) {
                days = Long.parseLong(value.substring(0, dash));
                value = value.substring(dash + 1);
            }
            long seconds = 0;
            for (String part : value.split(":")) seconds = seconds * 60 + Long.parseLong(part.trim());
            return days * 86_400 + seconds;
        } catch (NumberFormatException e) {
            return 0; // receiver not running, or ps printed something else: treat as just started
        }
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

    private static String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines[lines.length - 1].trim();
    }
}
