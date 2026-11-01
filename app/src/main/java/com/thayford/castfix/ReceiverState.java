package com.thayford.castfix;

import android.os.SystemClock;

/**
 * Whether the Cast receiver holds its device credentials, decided from what the receiver logs during a
 * bounded observation window rather than from whatever happens to be left in logcat.
 *
 * <p>Why a window: on Google TV Streamer (kirkwood) the main ring buffer is 256 KiB and cast_shell alone
 * fills it in well under an hour, so "no failure line in the buffer" says nothing about the receiver's
 * state. While loading fails the receiver retries once a minute forever, so a failure line inside the last
 * {@link #WINDOW_S} seconds is a reliable "broken", and no failure line across a complete window is a
 * reliable "fine". A single snapshot can only prove the first of those.
 *
 * <p>All timestamps are epoch seconds taken from the device, and the log is read with {@code -v epoch}, so
 * the check does not break at midnight, across a timezone change, or when the clock steps (NTP right after
 * boot).
 */
final class ReceiverState {

    /** Log tag of the credentials provisioner; it logs the failure and the retry. */
    private static final String TAG_PROVISIONED = "cast_ClientAuthCredsFactoryProvisioned";
    /** Log tag of the provisioning loop; it logs each attempt. */
    private static final String TAG_PROVISIONING = "cast_ClientAuthCredsProvisioning";

    static final String CREDS_LOADED = "Created singleton instance of ClientAuthSigner";
    static final String CREDS_MISSING = "Cannot get device credentials";
    static final String CREDS_LOADING = "Loading factory credentials";

    /**
     * The success line is logged by the native shell under the tag {@code cast_shell}, not by the provisioner,
     * so filtering by tag would miss it. Measured on kirkwood: the only line carrying it is
     * {@code I cast_shell: [...client_auth_signer.cc(173)] Created singleton instance of ClientAuthSigner.}
     * Filter by content instead, which is also what let the broken {tag|tag} filterspec be replaced.
     */
    static final String CONTENT_FILTER =
            "-e \"" + CREDS_LOADED + "|" + CREDS_MISSING + "|" + CREDS_LOADING + "\"";

    /**
     * The receiver retries once a minute; the window needs to span more than two retries so that a healthy
     * receiver cannot be mistaken for a broken one by a single missed cycle.
     */
    static final long WINDOW_S = 130;
    /** How often the window is sampled. */
    static final long POLL_MS = 5_000;
    /** One logcat read covering every line that says something about credentials. */
    static final String LOGCAT = "logcat -d -v epoch " + CONTENT_FILTER;
    /** Seconds-from-boot, used to notice the receiver restarting mid-window. */
    static final String RECEIVER_PID = "pidof " + CastFixer.CAST_RECEIVER;

    private ReceiverState() {
    }

    /** What the log said about the most recent credential load. */
    static final class Sample {
        /** Epoch seconds of the newest "Cannot get device credentials" line, or 0 if none in the buffer. */
        final long lastFailure;
        /** Epoch seconds of the newest "Created singleton instance of ClientAuthSigner", or 0. */
        final long lastLoaded;
        /** Epoch seconds of the newest attempt ("Loading factory credentials"), or 0. */
        final long lastAttempt;

        Sample(long lastFailure, long lastLoaded, long lastAttempt) {
            this.lastFailure = lastFailure;
            this.lastLoaded = lastLoaded;
            this.lastAttempt = lastAttempt;
        }

        /**
         * True when the most recent thing the receiver said about credentials is that they loaded. A later
         * attempt (e.g. after the receiver restarted) invalidates that, because the credentials live only in
         * the process that loaded them.
         */
        boolean loadedIsNewest() {
            return lastLoaded > 0 && lastLoaded >= lastFailure && lastLoaded >= lastAttempt;
        }

        /** True when the newest thing said is a load attempt with no load recorded after it. */
        boolean attemptNewerThanLoad() {
            return lastAttempt > 0 && lastAttempt > lastLoaded;
        }
    }

    /** Parses one {@code logcat -d -v epoch} dump. Never throws; unparsable lines are ignored. */
    static Sample parse(String castLog) {
        long failure = 0;
        long loaded = 0;
        long attempt = 0;
        if (castLog == null) return new Sample(0, 0, 0);
        for (String line : castLog.split("\n")) {
            long at = epochOf(line);
            if (at <= 0) continue;
            // Order matters: "Retry loading factory credentials" must not count as an attempt, or a broken
            // receiver would look like it is forever in the middle of a load.
            if (line.contains(CREDS_MISSING)) {
                if (at > failure) failure = at;
            } else if (line.contains(CREDS_LOADED)) {
                if (at > loaded) loaded = at;
            } else if (line.contains(CREDS_LOADING)) {
                if (at > attempt) attempt = at;
            }
        }
        return new Sample(failure, loaded, attempt);
    }

    /** The leading epoch field of a {@code -v epoch} line, truncated to whole seconds, or 0. */
    static long epochOf(String line) {
        String value = line.trim();
        int end = 0;
        while (end < value.length() && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) return 0;
        String number = value.substring(0, end);
        // A "10-31 05:49:34.000" line (the default format, e.g. from a command that forgot -v epoch) starts
        // with digits too. An epoch token is never followed by '-', and logcat prints it with a fraction.
        if (end < value.length() && value.charAt(end) == '-') return 0;
        if (!number.contains(".")) return 0;
        int dot = number.indexOf('.');
        number = number.substring(0, dot);
        // A real epoch is 9+ digits in this century; anything shorter is a date fragment or a stray number.
        if (number.length() < 9) return 0;
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Decides from one snapshot and the time the window has been open.
     *
     * @param sample            what the log holds now
     * @param now               epoch seconds on the device
     * @param watchedS          how long this window has been open, in seconds
     * @param receiverRestarted whether the receiver's pid changed during the window
     */
    static Verdict judge(Sample sample, long now, long watchedS, boolean receiverRestarted) {
        // The receiver keeps credentials only in the process that loaded them, so a restart resets everything.
        if (receiverRestarted) return Verdict.RECEIVER_RESTARTED;
        if (sample.loadedIsNewest()) return Verdict.LOADED;
        // A failure inside the window is proof, however old the rest of the buffer is.
        if (sample.lastFailure > 0 && now - sample.lastFailure <= WINDOW_S) return Verdict.FAILING;
        // An attempt with nothing after it means a load is in flight right now.
        if (sample.attemptNewerThanLoad() && now - sample.lastAttempt <= WINDOW_S) {
            return Verdict.IN_FLIGHT;
        }
        // Nothing has been said for a full window: only then is silence evidence of health.
        if (watchedS >= WINDOW_S) return Verdict.QUIET;
        return Verdict.UNKNOWN;
    }

    /**
     * Same decision, but only counting what has been logged since {@code notBefore} (epoch seconds).
     *
     * <p>This is what a "did the wizard fix it?" wait needs. The failure line from the last retry before the
     * wizard was enabled is still in the buffer and still inside {@link #WINDOW_S}, so {@link #judge} would
     * report FAILING half a second into a wait that is supposed to last a minute, and the run would give up
     * before the receiver ever retried with the provider present. Measured on kirkwood: the sequence on
     * success is four load/failure cycles about 1-2 s apart, then the signer line, so the first retry after
     * the wizard appears comes fast; a stale line must not pre-empt it.
     */
    static Verdict judgeSince(Sample sample, long now, long watchedS, boolean receiverRestarted,
                              long notBefore, boolean sawFreshFailure) {
        if (receiverRestarted) return Verdict.RECEIVER_RESTARTED;
        if (sample.lastLoaded > 0 && sample.lastLoaded >= notBefore && sample.loadedIsNewest()) {
            return Verdict.LOADED;
        }
        // Only a failure logged after the wizard came on says anything about the wizard.
        if (sawFreshFailure) return Verdict.FAILING;
        if (sample.attemptNewerThanLoad() && sample.lastAttempt >= notBefore
                && now - sample.lastAttempt <= WINDOW_S) {
            return Verdict.IN_FLIGHT;
        }
        // A full window with no failure after the change means the receiver is not failing any more. It stops
        // retrying once it succeeds, so nothing at all can also mean success.
        if (watchedS >= WINDOW_S) return Verdict.QUIET;
        return Verdict.UNKNOWN;
    }

    enum Verdict {
        /** The receiver logged the signer during this window. */
        LOADED,
        /** A "Cannot get device credentials" line inside the window. */
        FAILING,
        /** The receiver is loading right now (attempt newer than the last outcome). */
        IN_FLIGHT,
        /** A complete window with nothing said: the receiver is not retrying, so it is not failing. */
        QUIET,
        /** The receiver was replaced mid-window; the window no longer proves anything. */
        RECEIVER_RESTARTED,
        /** Not enough time watched yet to say anything. */
        UNKNOWN;

        /** True when this verdict means the credentials are in memory. */
        boolean healthy() {
            return this == LOADED || this == QUIET;
        }
    }

    /** Wall clock on the device, in epoch seconds, or 0 if it can't be read. */
    static long deviceNow(Shell shell) throws Exception {
        return parseLong(shell.run("date +%s"));
    }

    static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Watches the receiver for up to {@code timeoutMs} and returns the last verdict seen. Cheap calls: one
     * logcat read and one pidof per poll. Stops early as soon as the outcome is certain.
     */
    static Verdict watch(Shell shell, FixLog log, long timeoutMs) throws Exception {
        return watch(shell, log, timeoutMs, 0);
    }

    /**
     * Watches for a change that has already happened, ignoring anything logged before {@code notBefore}
     * (epoch seconds, 0 to consider the whole buffer). Used after enabling the setup wizard: the previous
     * retry's failure is still in the buffer and must not be mistaken for the wizard not working.
     */
    static Verdict watch(Shell shell, FixLog log, long timeoutMs, long notBefore) throws Exception {
        long start = SystemClock.elapsedRealtime();
        long watchedS = 0;
        String startPid = pidOf(shell);
        Verdict verdict = Verdict.UNKNOWN;
        boolean sawFreshFailure = false;
        long previousFailure = -1;
        while (true) {
            Sample sample = parse(shell.run(LOGCAT));
            long now = deviceNow(shell);
            String pid = pidOf(shell);
            boolean restarted = startPid != null && pid != null && !startPid.equals(pid);
            if (restarted) {
                // The window is about a process; a new process needs its own.
                start = SystemClock.elapsedRealtime();
                startPid = pid;
                watchedS = 0;
                restarted = false;
                previousFailure = -1;
            }
            if (notBefore > 0 && sample.lastFailure > 0 && sample.lastFailure >= notBefore) {
                // A failure timestamp we have not already counted: it happened after the change.
                if (previousFailure < 0 || sample.lastFailure > previousFailure) sawFreshFailure = true;
            }
            if (sample.lastFailure > 0) previousFailure = sample.lastFailure;
            watchedS = (SystemClock.elapsedRealtime() - start) / 1000;
            verdict = notBefore > 0
                    ? judgeSince(sample, now, watchedS, restarted, notBefore, sawFreshFailure)
                    : judge(sample, now, watchedS, restarted);
            if (verdict == Verdict.LOADED || verdict == Verdict.FAILING || verdict == Verdict.QUIET) {
                return verdict;
            }
            if (SystemClock.elapsedRealtime() - start >= timeoutMs) return verdict;
            Thread.sleep(POLL_MS);
        }
    }

    private static String pidOf(Shell shell) {
        try {
            String pid = shell.run(RECEIVER_PID).trim();
            return pid.isEmpty() ? null : pid.split("\n")[0].trim();
        } catch (Exception e) {
            return null;
        }
    }
}
