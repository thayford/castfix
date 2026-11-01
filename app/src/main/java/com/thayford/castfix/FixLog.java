package com.thayford.castfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Run log (files/castfix.log, also logcat tag CastFix) and the last result shown on screen. */
final class FixLog {
    private static final String TAG = "CastFix";
    private static final int KEEP_LINES = 300;

    private final File file;
    private final SharedPreferences prefs;

    FixLog(Context context) {
        file = new File(context.getFilesDir(), "castfix.log");
        prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE);
    }

    synchronized void add(String line) {
        Log.i(TAG, line);
        List<String> lines = tail(KEEP_LINES - 1);
        lines.add(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + line);
        // Written through a temp file and renamed: a kill mid-write must not truncate the log the owner needs
        // to diagnose exactly that kind of failure.
        File temp = new File(file.getParentFile(), "castfix.log.tmp");
        try {
            Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.w(TAG, "could not write the log", e);
        }
    }

    /** Logs the outcome of a run and keeps it for the screen; returns it for chaining. */
    String result(String message) {
        add(message);
        prefs.edit().putString("last", message).putLong("when", System.currentTimeMillis()).apply();
        return message;
    }

    String lastResult() {
        long when = prefs.getLong("when", 0);
        if (when == 0) return "Not run yet.";
        return prefs.getString("last", "") + "  ("
                + new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(when)) + ")";
    }

    void setPaired() {
        prefs.edit().putBoolean("paired", true).apply();
    }

    boolean isPaired() {
        return prefs.getBoolean("paired", false);
    }

    synchronized List<String> tail(int count) {
        List<String> lines = new ArrayList<>();
        if (file.exists()) {
            try {
                lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "could not read the log", e);
            }
        }
        return new ArrayList<>(lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }
}
