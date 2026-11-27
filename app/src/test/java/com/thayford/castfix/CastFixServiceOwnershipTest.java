package com.thayford.castfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Who may take the service down when a run finishes.
 *
 * <p>The service outlives individual runs: a run ends, its teardown is posted to the main thread, and a new
 * trigger (the boot receiver, the 15-minute job, the button) can claim the service inside that gap. A run that
 * finished earlier must then leave the service and its notification alone, or the newer run is cut in half
 * with nothing in the log to explain it.
 */
public class CastFixServiceOwnershipTest {

    @Test
    public void theLastRunTakesTheServiceDown() {
        assertTrue(CastFixService.ownsService(7, 7, false));
    }

    @Test
    public void aRunThatFinishedAfterAnotherTookOverLeavesItAlone() {
        // Run 7 finished and released the flag; run 8 claimed the service before run 7's teardown ran.
        assertFalse(CastFixService.ownsService(7, 8, true));
    }

    @Test
    public void anOlderRunNeverTakesDownANewerOneEvenWhenNeitherIsRunning() {
        // Both finished, and run 8's teardown already ran (currentStartId reset). Run 7's must not act as if
        // it were the owner.
        assertFalse(CastFixService.ownsService(7, 8, false));
    }

    @Test
    public void aRunningFlagBlocksAnyTeardownFromItsOwner() {
        // Defence in depth: while a run holds the flag, no teardown closes the service.
        assertFalse(CastFixService.ownsService(7, 7, true));
    }
}
