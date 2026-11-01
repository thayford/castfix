package com.thayford.castfix;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What a run is about to change, written before the first change and cleared when the run finishes.
 *
 * <p>Why it exists: a run has to enable setupwraith for up to two minutes. If the process is killed, adb
 * stalls past its retries, or the TV sleeps mid-run, setupwraith stays enabled. On Google TV Streamer
 * (kirkwood) that is the documented state in which system_server crash-loops: setupwraith's RecoveryActivity
 * takes Home, fails inside Task.moveToBack, and takes SystemUI down with it, roughly every 47 s. Nothing in
 * the app used to notice, because {@link BootReceiver} only reacts to BOOT_COMPLETED and the crash loop
 * restarts system_server before a boot-time run could finish.
 *
 * <p>So the record is the recovery path: any later start (boot, the button, or opening the app) finds it and
 * puts the device back before doing anything else.
 */
final class RunJournal {

    private static final String PREFS = "run";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_STARTED_AT = "started";
    private static final String KEY_TRIGGER = "trigger";
    private static final String KEY_ADB_WIFI_WAS_ON = "adb_wifi_was_on";
    private static final String KEY_ADB_TIMEOUT_WAS_SET = "adb_timeout_was_set";
    private static final String KEY_SETUP_ENABLED_BY_US = "setup_enabled_by_us";
    private static final String KEY_STOCK_HOME_WAS_OFF = "stock_home_was_off";
    private static final String KEY_HOME_BEFORE = "home_before";

    private final SharedPreferences prefs;

    RunJournal(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when a run recorded what it was about to change and never recorded finishing. */
    boolean isActive() {
        return prefs.getBoolean(KEY_ACTIVE, false);
    }

    long startedAt() {
        return prefs.getLong(KEY_STARTED_AT, 0);
    }

    /** The trigger of the run that did not finish, for the log and the screen. */
    String trigger() {
        return prefs.getString(KEY_TRIGGER, "unknown");
    }

    boolean adbWifiWasOn() {
        return prefs.getBoolean(KEY_ADB_WIFI_WAS_ON, true);
    }

    boolean adbTimeoutWasSet() {
        return prefs.getBoolean(KEY_ADB_TIMEOUT_WAS_SET, false);
    }

    boolean setupEnabledByUs() {
        return prefs.getBoolean(KEY_SETUP_ENABLED_BY_US, false);
    }

    boolean stockHomeWasOff() {
        return prefs.getBoolean(KEY_STOCK_HOME_WAS_OFF, false);
    }

    String homeBefore() {
        return prefs.getString(KEY_HOME_BEFORE, "");
    }

    /**
     * Records the intent to change things. Called *before* the first mutating command, and committed
     * synchronously: a record written after the change would not survive the kill it exists to cover.
     */
    void begin(String trigger, boolean adbWifiWasOn, boolean adbTimeoutWasSet, String homeBefore) {
        prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_TRIGGER, trigger)
                .putBoolean(KEY_ADB_WIFI_WAS_ON, adbWifiWasOn)
                .putBoolean(KEY_ADB_TIMEOUT_WAS_SET, adbTimeoutWasSet)
                .putString(KEY_HOME_BEFORE, homeBefore == null ? "" : homeBefore)
                // Cleared individually as the run reaches them, so recovery knows how far it got.
                .putBoolean(KEY_SETUP_ENABLED_BY_US, false)
                .putBoolean(KEY_STOCK_HOME_WAS_OFF, false)
                .commit();
    }

    /** Records that this run enabled setupwraith, so recovery knows to disable it. */
    void setupEnabled() {
        prefs.edit().putBoolean(KEY_SETUP_ENABLED_BY_US, true).commit();
    }

    /** Records that the stock launcher was off before this run touched it. */
    void markStockHomeWasOff() {
        prefs.edit().putBoolean(KEY_STOCK_HOME_WAS_OFF, true).commit();
    }

    /** Clears the record: everything this run changed has been put back. */
    void finish() {
        prefs.edit().putBoolean(KEY_ACTIVE, false).commit();
    }
}
