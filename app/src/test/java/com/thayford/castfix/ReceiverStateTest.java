package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Decision table for {@link ReceiverState}. The shapes here are copied from what a Google TV Streamer
 * (kirkwood, UTTK.260317.003) actually prints, including the 10-space left padding that `logcat -v epoch`
 * puts in front of the timestamp.
 */
public class ReceiverStateTest {

    /** One cycle of the broken loop, as measured on the device. */
    private static final String FAILING =
            "         1793438324.217  2165  2660 I cast_ClientAuthCredsProvisioning: Loading factory credentials for 1P device.\n"
            + "         1793438324.219  2165  2660 E cast_ClientAuthCredsFactoryProvisioned: Cannot get device credentials\n"
            + "         1793438324.219  2165  2660 I cast_ClientAuthCredsProvisioning: Retry loading factory credentials\n";

    private static final String LOADED =
            "         1793438000.100  2165  2611 I cast_shell: [1031/054413.543619:INFO:client_auth_signer.cc(180)] "
            + "Created singleton instance of ClientAuthSigner\n";

    @Test
    public void parsesPaddedEpochAndTruncatesFraction() {
        assertEquals(1793438324L, ReceiverState.epochOf(
                "         1793438324.219  2165  2660 E tag: text"));
    }

    @Test
    public void epochOfRejectsNonLogLines() {
        assertEquals(0, ReceiverState.epochOf("--------- beginning of main"));
        assertEquals(0, ReceiverState.epochOf(""));
        assertEquals(0, ReceiverState.epochOf("10-31 05:49:34.000  2165  2660 E tag: text"));
    }

    @Test
    public void retryLineIsNotAnAttempt() {
        // "Retry loading factory credentials" also contains "Loading factory credentials" only in lowercase
        // form; mistaking it for an attempt would make a broken receiver look permanently in flight.
        ReceiverState.Sample sample = ReceiverState.parse(FAILING);
        assertEquals(1793438324L, sample.lastAttempt);
        assertEquals(1793438324L, sample.lastFailure);
        assertEquals(0, sample.lastLoaded);
    }

    @Test
    public void recentFailureIsBroken() {
        ReceiverState.Sample sample = ReceiverState.parse(FAILING);
        assertEquals(ReceiverState.Verdict.FAILING,
                ReceiverState.judge(sample, 1793438324L + 30, 30, false));
    }

    @Test
    public void failureJustInsideWindowIsStillBroken() {
        ReceiverState.Sample sample = ReceiverState.parse(FAILING);
        assertEquals(ReceiverState.Verdict.FAILING,
                ReceiverState.judge(sample, 1793438324L + ReceiverState.WINDOW_S, 200, false));
    }

    @Test
    public void failureOutsideWindowWithFullObservationIsQuiet() {
        // This is the case the old code got backwards: a rotated buffer used to mean "healthy".
        ReceiverState.Sample sample = ReceiverState.parse(FAILING);
        assertEquals(ReceiverState.Verdict.QUIET, ReceiverState.judge(
                sample, 1793438324L + ReceiverState.WINDOW_S + 60, ReceiverState.WINDOW_S + 60, false));
    }

    @Test
    public void rotatedBufferWithoutAFullWindowIsUnknownNotHealthy() {
        // No failure line at all (rotated out) and not enough elapsed time: must NOT claim healthy.
        ReceiverState.Sample sample = ReceiverState.parse("");
        assertEquals(ReceiverState.Verdict.UNKNOWN, ReceiverState.judge(sample, 1_000_000L, 10, false));
    }

    @Test
    public void rotatedBufferAfterAFullQuietWindowIsQuiet() {
        ReceiverState.Sample sample = ReceiverState.parse("");
        assertEquals(ReceiverState.Verdict.QUIET, ReceiverState.judge(
                sample, 1_000_000L, ReceiverState.WINDOW_S, false));
    }

    @Test
    public void loadedIsHealthy() {
        ReceiverState.Sample sample = ReceiverState.parse(LOADED);
        assertEquals(ReceiverState.Verdict.LOADED, ReceiverState.judge(sample, 1793438000L + 5, 5, false));
        assertTrue(ReceiverState.Verdict.LOADED.healthy());
    }

    @Test
    public void loadAfterFailureWins() {
        // Same buffer holding an old failure and a newer load: the receiver recovered.
        ReceiverState.Sample sample = ReceiverState.parse(FAILING + LOADED.replace("1793438000", "1793438400"));
        assertEquals(1793438400L, sample.lastLoaded);
        assertEquals(ReceiverState.Verdict.LOADED, ReceiverState.judge(sample, 1793438401L, 5, false));
    }

    @Test
    public void attemptAfterLoadMeansInFlight() {
        // The receiver restarted and is loading again; the old load no longer proves anything. No failure yet,
        // so the only thing known is that a load is in progress.
        String restarted = LOADED + "         1793438400.100  2165  2660 I cast_ClientAuthCredsProvisioning: "
                + "Loading factory credentials for 1P device.\n";
        ReceiverState.Sample sample = ReceiverState.parse(restarted);
        assertFalse(sample.loadedIsNewest());
        assertTrue(sample.attemptNewerThanLoad());
        assertEquals(ReceiverState.Verdict.IN_FLIGHT, ReceiverState.judge(sample, 1793438401L, 5, false));
    }

    @Test
    public void attemptThenFailureIsFailingNotInFlight() {
        // The plain restart cycle: an attempt immediately followed by its failure must read as broken.
        // judge() checks the failure first, which is what makes this ordering safe.
        String restarted = LOADED + FAILING.replace("1793438324", "1793438400");
        ReceiverState.Sample sample = ReceiverState.parse(restarted);
        assertFalse(sample.loadedIsNewest());
        assertEquals(ReceiverState.Verdict.FAILING, ReceiverState.judge(sample, 1793438401L, 5, false));
    }

    @Test
    public void receiverRestartInvalidatesTheWindow() {
        ReceiverState.Sample sample = ReceiverState.parse(LOADED);
        assertEquals(ReceiverState.Verdict.RECEIVER_RESTARTED,
                ReceiverState.judge(sample, 1793438001L, 5, true));
        assertFalse(ReceiverState.Verdict.RECEIVER_RESTARTED.healthy());
    }

    @Test
    public void clockSteppingBackIsNotMistakenForHealth() {
        // NTP can pull the clock backwards after boot. A failure "in the future" must not read as healthy.
        ReceiverState.Sample sample = ReceiverState.parse(FAILING);
        assertEquals(ReceiverState.Verdict.FAILING,
                ReceiverState.judge(sample, 1793438324L - 3600, 5, false));
    }

    /**
     * The line that proves the receiver loaded its credentials is logged by the native shell under
     * {@code cast_shell}, not by the provisioner. A tag filter misses it, which is how a real run on kirkwood
     * reported "did not load" while the 8009 handshake was already signing with a 256-byte signature.
     */
    @Test
    public void singletonLineFromCastShellTagIsParsed() {
        String real = "         1793439224.514  2165  2165 I cast_shell: "
                + "[1031/063344.514834:INFO:client_auth_signer.cc(173)] "
                + "Created singleton instance of ClientAuthSigner.\n";
        ReceiverState.Sample sample = ReceiverState.parse(real);
        assertEquals(1793439224L, sample.lastLoaded);
        assertTrue(sample.loadedIsNewest());
        assertEquals(ReceiverState.Verdict.LOADED, ReceiverState.judge(sample, 1793439225L, 5, false));
    }

    @Test
    public void contentFilterCoversEverySignal() {
        // Whatever the shell prints, parse() must be able to see all three messages.
        assertTrue(ReceiverState.CONTENT_FILTER.contains(ReceiverState.CREDS_LOADED));
        assertTrue(ReceiverState.CONTENT_FILTER.contains(ReceiverState.CREDS_MISSING));
        assertTrue(ReceiverState.CONTENT_FILTER.contains(ReceiverState.CREDS_LOADING));
    }

    @Test
    public void watchCommandUsesEpochAndContentFilter() {
        // A "MM-DD hh:mm:ss" marker is ambiguous across midnight and moves backwards when the clock steps,
        // and a tag filter misses the signer line because cast_shell logs it.
        assertTrue(ReceiverState.LOGCAT, ReceiverState.LOGCAT.contains("-v epoch"));
        assertTrue(ReceiverState.LOGCAT, ReceiverState.LOGCAT.contains("-e \""));
        assertFalse(ReceiverState.LOGCAT, ReceiverState.LOGCAT.contains("-T "));
    }

    @Test
    public void staleFailureBeforeTheChangeDoesNotCount() {
        // The bug this guards: the retry that failed just before the wizard was enabled is still inside the
        // window, so judging on it gave up half a second in. Only failures after the change may count.
        ReceiverState.Sample stale = ReceiverState.parse(FAILING); // failure at ...324
        long enabledAt = 1793438325L;                              // one second later
        assertEquals(ReceiverState.Verdict.UNKNOWN,
                ReceiverState.judgeSince(stale, enabledAt + 1, 1, false, enabledAt, false));
    }

    @Test
    public void freshFailureAfterTheChangeCounts() {
        long enabledAt = 1793438325L;
        ReceiverState.Sample fresh = ReceiverState.parse(FAILING.replace("1793438324", "1793438385"));
        assertEquals(ReceiverState.Verdict.FAILING,
                ReceiverState.judgeSince(fresh, 1793438386L, 60, false, enabledAt, true));
    }

    @Test
    public void loadAfterTheChangeSucceeds() {
        long enabledAt = 1793438325L;
        ReceiverState.Sample loaded = ReceiverState.parse(LOADED.replace("1793438000", "1793438400"));
        assertEquals(ReceiverState.Verdict.LOADED,
                ReceiverState.judgeSince(loaded, 1793438401L, 10, false, enabledAt, false));
    }

    @Test
    public void loadBeforeTheChangeDoesNotProveTheWizardWorked() {
        // A load from an earlier run is in the buffer but says nothing about this attempt.
        long enabledAt = 1793438325L;
        ReceiverState.Sample old = ReceiverState.parse(LOADED); // loaded at ...000
        assertEquals(ReceiverState.Verdict.UNKNOWN,
                ReceiverState.judgeSince(old, enabledAt + 5, 5, false, enabledAt, false));
    }

    @Test
    public void silenceForAFullWindowAfterTheChangeSucceeds() {
        // The receiver stops retrying once it succeeds, so a quiet window is success, not ignorance.
        long enabledAt = 1793438325L;
        ReceiverState.Sample nothing = ReceiverState.parse("");
        assertEquals(ReceiverState.Verdict.QUIET,
                ReceiverState.judgeSince(nothing, enabledAt + ReceiverState.WINDOW_S + 1,
                        ReceiverState.WINDOW_S, false, enabledAt, false));
    }

    @Test
    public void onlyLoadedAndQuietAreHealthy() {
        for (ReceiverState.Verdict verdict : ReceiverState.Verdict.values()) {
            boolean expected = verdict == ReceiverState.Verdict.LOADED || verdict == ReceiverState.Verdict.QUIET;
            assertEquals(verdict.name(), expected, verdict.healthy());
        }
    }
}
