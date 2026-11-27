package com.thayford.castfix;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What a run is about to change, written before each change and cleared when the run has put everything
 * back. A run keeps the setup wizard on for up to a minute or two; if the app is killed, adb stalls past its
 * retries or the TV sleeps in that window, the wizard would stay on. On Google TV Streamer (kirkwood) that is
 * the state in which system_server crash-loops (setupwraith's RecoveryActivity takes Home and fails in
 * Task.moveToBack about every 47 s); elsewhere it hands Home to the stock launcher. So any later start (boot,
 * the 15-minute check, the button, or opening the app) finds the record and puts things back first.
 *
 * <p>Every write is a synchronous commit(): a record written after the change wouldn't survive the kill it
 * exists for. From PR #1 by Sebastián Valdivia Loyola (svaldivia12).
 */
final class RunJournal {
    private final SharedPreferences prefs;

    RunJournal(Context context) {
        prefs = context.getSharedPreferences("run", Context.MODE_PRIVATE);
    }

    boolean isActive() {
        return prefs.getBoolean("active", false);
    }

    String trigger() {
        return prefs.getString("trigger", "unknown");
    }

    boolean setupEnabledByUs() {
        return prefs.getBoolean("setup_enabled_by_us", false);
    }

    boolean stockHomeWasOff() {
        return prefs.getBoolean("stock_home_was_off", false);
    }

    String homeBefore() {
        return prefs.getString("home_before", "");
    }

    void begin(String trigger, String homeBefore) {
        prefs.edit()
                .putBoolean("active", true)
                .putLong("started", System.currentTimeMillis())
                .putString("trigger", trigger)
                .putString("home_before", homeBefore == null ? "" : homeBefore)
                .putBoolean("setup_enabled_by_us", false)
                .putBoolean("stock_home_was_off", false)
                .commit();
    }

    /** This run has the wizard on (or found it on and will switch it off), so recovery must switch it off. */
    void setupEnabled() {
        prefs.edit().putBoolean("setup_enabled_by_us", true).commit();
    }

    void setupDisabled() {
        prefs.edit().putBoolean("setup_enabled_by_us", false).commit();
    }

    void markStockHomeWasOff() {
        prefs.edit().putBoolean("stock_home_was_off", true).commit();
    }

    void finish() {
        prefs.edit().putBoolean("active", false).commit();
    }
}
