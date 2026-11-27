package com.thayford.castfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Run log (files/castfix.log, also logcat tag CastFix) and the last result shown on screen. */
final class FixLog {
    private static final String TAG = "CastFix";
    private static final int KEEP_LINES = 300;
    /** SimpleDateFormat is not thread-safe and a run logs from its worker thread while the screen reads. */
    private final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private final File file;
    private final SharedPreferences prefs;
    /** Mirrors the file, so a line costs an append rather than a read plus a rewrite of the whole log. */
    private final List<String> lines = new ArrayList<>();
    private boolean loaded = false;

    FixLog(Context context) {
        file = new File(context.getFilesDir(), "castfix.log");
        prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE);
    }

    synchronized void add(String line) {
        Log.i(TAG, line);
        load();
        lines.add(stamp.format(new Date()) + "  " + line);
        while (lines.size() > KEEP_LINES) lines.remove(0);
        // Through a temp file and a rename, so a kill mid-write can't truncate the log needed to diagnose
        // exactly that kind of failure (PR #1, svaldivia12).
        File temp = new File(file.getParentFile(), "castfix.log.tmp");
        try {
            Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.w(TAG, "could not write the log", e);
        }
    }

    /** Reads the file once into memory; every later line is an append. */
    private void load() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        try {
            lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "could not read the log", e);
        }
    }

    /** Logs the outcome of a run and keeps it for the screen; returns it for chaining. */
    String result(String message) {
        add(message);
        prefs.edit()
                .putString("last", message)
                .putLong("when", System.currentTimeMillis())
                // Time since boot when the run ended. It resets on a reboot, so a later value smaller than
                // this one means the device restarted and the receiver lost its credentials with it.
                .putLong("elapsedAtResult", SystemClock.elapsedRealtime())
                .apply();
        return message;
    }

    /** True when the last run's result predates a reboot of this device. */
    static boolean restartedSince(long elapsedAtResult, long now) {
        return elapsedAtResult > 0 && now < elapsedAtResult;
    }

    /**
     * The last run as one line for the screen: when it ran, what it concluded, and a warning if the device
     * restarted since (a reboot throws the receiver's credentials away).
     */
    String lastRunSummary() {
        long when = prefs.getLong("when", 0);
        if (when == 0) return "Not run yet.";
        String whenText = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(when));
        String note = "";
        if (restartedSince(prefs.getLong("elapsedAtResult", 0), SystemClock.elapsedRealtime())) {
            note = " The TV has restarted since, so casting needs fixing again.";
        }
        return prefs.getString("last", "") + "  (" + whenText + ")" + note;
    }

    void setPaired() {
        prefs.edit().putBoolean("paired", true).apply();
    }

    boolean isPaired() {
        return prefs.getBoolean("paired", false);
    }

    synchronized List<String> tail(int count) {
        load();
        return new ArrayList<>(lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }
}
