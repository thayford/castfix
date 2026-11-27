package com.thayford.castfix;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.Inet4Address;

/**
 * An adb shell on this TV: Wireless debugging on, a loopback connection with the paired key, the task,
 * then Wireless debugging off again. One session at a time, because each one switches the setting.
 */
final class SelfAdb {
    interface Task<T> {
        T run(Shell shell) throws Exception;
    }

    /** Why a session couldn't start; the message is meant for the screen. */
    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }

    private static final String ADB_WIFI = "adb_wifi_enabled";
    private static final Object LOCK = new Object();

    private SelfAdb() {
    }

    static <T> T run(Context context, FixLog log, long wifiWaitMs, Task<T> task) throws Exception {
        synchronized (LOCK) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new Unavailable("Needs WRITE_SECURE_SETTINGS, granted once from a computer (README, Setup).");
            }
            String ip = waitForWifi(context, wifiWaitMs);
            if (ip == null) throw new Unavailable("No Wi-Fi after " + wifiWaitMs / 1000 + " s; nothing changed.");
            ContentResolver resolver = context.getContentResolver();
            boolean debuggingWasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1;
            Settings.Global.putInt(resolver, ADB_WIFI, 1);
            // A paired key otherwise lapses after 7 days without a connection, and restarts can be further apart.
            Settings.Global.putLong(resolver, "adb_allowed_connection_time", 0L);
            try (Shell shell = new Shell(context, ip, log)) {
                if (!shell.connect()) throw new Unavailable("Could not reach Wireless debugging on this TV.");
                grantMissing(context, shell, log);
                return task.run(shell);
            } finally {
                // Troubleshooting: `settings put global castfix_keep_debugging 1` keeps it on to read the logs.
                if (!debuggingWasOn && Settings.Global.getInt(resolver, "castfix_keep_debugging", 0) != 1) {
                    Settings.Global.putInt(resolver, ADB_WIFI, 0);
                    log.add("Wireless debugging switched off again");
                }
            }
        }
    }

    /** True when the permissions only adb can give (beyond WRITE_SECURE_SETTINGS) are all in place. */
    static boolean hasGrants(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power.isIgnoringBatteryOptimizations(context.getPackageName())
                && context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
                && hasUsageAccess(context);
    }

    static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Gives the app what the 15-minute check, the cast pause and the remote watchdog need. */
    private static void grantMissing(Context context, Shell shell, FixLog log) throws Exception {
        String pkg = context.getPackageName();
        PowerManager power = context.getSystemService(PowerManager.class);
        if (!power.isIgnoringBatteryOptimizations(pkg)) {
            shell.run("dumpsys deviceidle whitelist +" + pkg);
            log.add("battery optimization off, so the checks also run while the TV sleeps");
        }
        if (context.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            shell.run("pm grant " + pkg + " android.permission.DUMP");
            log.add("granted DUMP, to read the Bluetooth state for the remote watchdog");
        }
        if (!hasUsageAccess(context)) {
            shell.run("appops set " + pkg + " GET_USAGE_STATS allow");
            log.add("granted usage access, to see when a cast is on screen");
        }
    }

    /** The Wi-Fi IPv4 address once Wi-Fi is up, or null on timeout. */
    static String waitForWifi(Context context, long timeoutMs) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            LinkProperties link = network == null ? null : cm.getLinkProperties(network);
            if (caps != null && link != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                for (LinkAddress address : link.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null;
            SystemClock.sleep(1_000);
        }
    }
}
