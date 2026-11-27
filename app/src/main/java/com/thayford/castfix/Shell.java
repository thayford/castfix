package com.thayford.castfix;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.muntashirakon.adb.AdbStream;

/**
 * adb shell on this TV, with a time limit on every command. libadb's open() waits for the OKAY without a
 * timeout and can miss it when adbd answers before the wait starts (likely over loopback on a busy CPU
 * just after boot), which hung a whole run. A stalled command closes the connection, which wakes the
 * stuck thread, and is retried on a fresh one; every command used here is safe to repeat.
 */
final class Shell implements Closeable {
    private static final long COMMAND_TIMEOUT_MS = 20_000;
    private static final long FIRST_TRY_TIMEOUT_MS = 10_000;
    /** adbd can take a while to bring Wireless debugging up right after boot (PR #3, lukasmeggle). */
    private static final long CONNECT_TIMEOUT_MS = 90_000;
    private static final String RC_MARKER = "__castfix_rc=";
    private static final int ATTEMPTS = 3;

    private final Context context;
    private final String ip;
    private final FixLog log;
    private final ExecutorService pool = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "castfix-adb");
        thread.setDaemon(true);
        return thread;
    });
    private AdbManager adb;

    Shell(Context context, String ip, FixLog log) {
        this.context = context;
        this.ip = ip;
        this.log = log;
    }

    /** Connects to Wireless debugging on this TV; false if it didn't come up in time. */
    boolean connect() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        boolean reportedMissing = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            int port = AdbPort.find(context, ip, 10_000);
            if (port <= 0 && !reportedMissing) {
                // Once per run: a boot run that never found the port used to end with no hint of why.
                log.add("no Wireless debugging service over mDNS yet (adb_wifi_enabled="
                        + Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) + ")");
                reportedMissing = true;
            }
            if (port > 0) {
                for (String host : new String[]{"127.0.0.1", ip}) {
                    try {
                        AdbManager manager = new AdbManager(context);
                        Future<Boolean> attempt = pool.submit(() -> manager.connect(host, port));
                        if (attempt.get(30, TimeUnit.SECONDS)) {
                            adb = manager;
                            log.add("adb connected on " + host + ":" + port);
                            return true;
                        }
                        closeQuietly(manager);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        log.add("adb " + host + ":" + port + ": " + describe(e));
                    }
                }
            }
            Thread.sleep(3_000);
        }
        return false;
    }

    /** Runs a shell command and returns its output, reconnecting and retrying if it stalls. */
    String run(String command) throws Exception {
        for (int attempt = 1; ; attempt++) {
            AdbManager current = adb;
            Future<String> result = pool.submit(() -> exec(current, command));
            try {
                // Every command here answers in a second or two, so a first try that hasn't in 10 s is the
                // lost wakeup, not a slow command; later tries get the full limit.
                return result.get(attempt == 1 ? FIRST_TRY_TIMEOUT_MS : COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                log.add("adb stalled on `" + brief(command) + "` (" + attempt + "/" + ATTEMPTS + "): " + describe(e));
                closeQuietly(current);
                result.cancel(true);
                if (attempt == ATTEMPTS) throw new IOException("adb kept stalling on `" + brief(command) + "`");
                if (!connect()) throw new IOException("could not reconnect to adb");
            }
        }
    }

    /**
     * Runs a command and also returns its exit status. adbd's shell stream merges stderr into stdout, so a
     * refused `pm enable` prints its error as ordinary output and would otherwise look like it worked.
     * Only used where the status matters, so the other commands stay exactly as they were
     * (from PR #1, svaldivia12).
     */
    Result runChecked(String command) throws Exception {
        return Result.parse(run(command + "; echo " + RC_MARKER + "$?"));
    }

    /** A command's output and exit status (-1 when the status didn't come back). */
    static final class Result {
        final String output;
        final int status;

        Result(String output, int status) {
            this.output = output;
            this.status = status;
        }

        static Result parse(String raw) {
            int marker = raw.lastIndexOf(RC_MARKER);
            if (marker < 0) return new Result(raw, -1);
            int status;
            try {
                status = Integer.parseInt(raw.substring(marker + RC_MARKER.length()).trim());
            } catch (NumberFormatException e) {
                status = -1;
            }
            return new Result(raw.substring(0, marker), status);
        }

        /**
         * True only when the shell positively reported success. An unknown status (-1, the stream ended
         * before the marker) is not success: callers verify the resulting device state anyway, and treating
         * unknown as success is what let an interrupted `pm enable` look like it had worked.
         *
         * <p>The owner kept the opposite rule in 1.3 ("unknown counts as success, callers check the state")
         * because `pm enable` is verified in the package list. That is still true, but every caller of this
         * is a `pm` command whose effect is confirmed separately, so failing closed can only cost an extra
         * log line, never a wrong decision.
         */
        boolean ok() {
            return status == 0;
        }

        /** The first line of what the command printed, or its status when it printed nothing. */
        String problem() {
            String text = output.trim();
            int newline = text.indexOf('\n');
            if (newline > 0) text = text.substring(0, newline);
            if (text.isEmpty()) return status < 0 ? "no exit status came back" : "exit " + status;
            return text;
        }
    }

    @Override
    public void close() {
        closeQuietly(adb);
        pool.shutdownNow();
    }

    private static String exec(AdbManager adb, String command) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (AdbStream stream = adb.openStream("shell:" + command);
             InputStream in = stream.openInputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            try {
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            } catch (IOException e) {
                // libadb ends a finished command's stream this way
                if (!"Stream closed.".equals(e.getMessage())) throw e;
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Closes off the calling thread: closing is what wakes a thread stuck in open(). */
    private void closeQuietly(AdbManager manager) {
        if (manager == null) return;
        Future<?> closing = pool.submit(() -> {
            manager.close();
            return null;
        });
        try {
            closing.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // abandoned; Wireless debugging going off at the end drops the socket anyway
        }
    }

    private static String brief(String command) {
        return command.length() <= 40 ? command : command.substring(0, 40) + "…";
    }

    private static String describe(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause instanceof TimeoutException ? "no answer" : cause.toString();
    }
}
