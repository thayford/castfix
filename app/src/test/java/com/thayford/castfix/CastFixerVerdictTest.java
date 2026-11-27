package com.thayford.castfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When a run may skip its work, and when a failure is worth retrying.
 *
 * <p>Both rules decide something the user reads as fact, and both are easy to get wrong in a way no compiler
 * catches: the shortcut once answered "Casting already works." on a kirkwood whose setup wizard was left on,
 * which is the state that crash-loops system_server there.
 */
public class CastFixerVerdictTest {

    @Test
    public void signingReceiverWithWizardOffIsAlreadyWorking() {
        assertTrue(CastFixer.alreadyWorking(Boolean.TRUE, Boolean.FALSE));
    }

    @Test
    public void signingReceiverWithWizardOnIsNotAlreadyWorking() {
        // The bug found on hardware: the receiver signs, so the old shortcut returned "Casting already
        // works." while the wizard was on. On a replacement-launcher setup that is the crash-loop state.
        assertFalse(CastFixer.alreadyWorking(Boolean.TRUE, Boolean.TRUE));
    }

    @Test
    public void unreadableWizardStateIsNotAlreadyWorking() {
        // Unknown counts as on: a full run costs seconds, a wrong verdict hides the risk.
        assertFalse(CastFixer.alreadyWorking(Boolean.TRUE, null));
    }

    @Test
    public void unsignedReceiverIsNeverAlreadyWorking() {
        assertFalse(CastFixer.alreadyWorking(Boolean.FALSE, Boolean.FALSE));
        assertFalse(CastFixer.alreadyWorking(null, Boolean.FALSE));
    }

    @Test
    public void networkFailuresAreWorthRetrying() {
        // The exact strings SelfAdb and CastFixer produce, so a rewording that breaks the match shows up here.
        assertTrue(CastFixer.transientFailure("No Wi-Fi after 90 s; nothing changed."));
        assertTrue(CastFixer.transientFailure(
                "Wireless debugging would not stay on: allow it on this network for good (README)."));
        assertTrue(CastFixer.transientFailure("Could not reach Wireless debugging on this TV."));
        assertTrue(CastFixer.transientFailure("Cast credentials did not load; run it again."));
    }

    @Test
    public void permanentFailuresAreNotRetried() {
        // Retrying these wastes a minute to reach the same answer.
        assertFalse(CastFixer.transientFailure("Needs WRITE_SECURE_SETTINGS, granted once from a computer."));
        assertFalse(CastFixer.transientFailure("This TV has no Cast receiver (mediashell); nothing to fix."));
        assertFalse(CastFixer.transientFailure("Casting ready."));
        assertFalse(CastFixer.transientFailure(null));
    }
}
