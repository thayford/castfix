package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The run button's label and enabled state. Both are derived from the same three booleans, so these tests fix
 * the ordering as well as the strings: the ordering is where this is easy to get wrong, and a wrong ordering
 * shows the owner a verdict that is not current.
 */
public class MainActivityLabelTest {

    @Test
    public void idleAndBrokenOffersTheAction() {
        assertEquals(MainActivity.LABEL_ACTION, MainActivity.labelFor(false, false, false));
        assertTrue(MainActivity.enabledFor(false, false, false));
    }

    @Test
    public void checkingIsLabelledAndLocked() {
        // Nothing is known yet, so the button must not offer to act on a verdict that has not arrived.
        assertEquals(MainActivity.LABEL_CHECKING, MainActivity.labelFor(true, false, false));
        assertFalse(MainActivity.enabledFor(true, false, false));
    }

    @Test
    public void runningIsLabelledAndLocked() {
        assertEquals(MainActivity.LABEL_FIXING, MainActivity.labelFor(false, true, false));
        assertFalse(MainActivity.enabledFor(false, true, false));
    }

    @Test
    public void workingIsLabelledAndLocked() {
        assertEquals(MainActivity.LABEL_FIXED, MainActivity.labelFor(false, false, true));
        assertFalse(MainActivity.enabledFor(false, false, true));
    }

    @Test
    public void runningOutranksChecking() {
        // An adb-triggered run can begin while a check is still in flight. "Fixing…" describes what is
        // happening then; "Checking…" would be stale.
        assertEquals(MainActivity.LABEL_FIXING, MainActivity.labelFor(true, true, false));
        assertFalse(MainActivity.enabledFor(true, true, false));
    }

    @Test
    public void checkingOutranksAStaleHealthyVerdict() {
        // The previous probe said fine, a new one is running: showing "Fixed" would present a verdict the app
        // does not currently believe.
        assertEquals(MainActivity.LABEL_CHECKING, MainActivity.labelFor(true, false, true));
        assertFalse(MainActivity.enabledFor(true, false, true));
    }

    @Test
    public void onlyTheIdleBrokenStateIsEverPressable() {
        for (int bits = 0; bits < 8; bits++) {
            boolean probing = (bits & 1) != 0;
            boolean busy = (bits & 2) != 0;
            boolean healthy = (bits & 4) != 0;
            boolean expected = !probing && !busy && !healthy;
            assertEquals("probing=" + probing + " busy=" + busy + " healthy=" + healthy,
                    expected, MainActivity.enabledFor(probing, busy, healthy));
        }
    }

    @Test
    public void everyStateHasItsOwnLabel() {
        // Four states, four labels: no two disabled states collapse into the same word.
        String[] labels = {
                MainActivity.labelFor(false, false, false),
                MainActivity.labelFor(true, false, false),
                MainActivity.labelFor(false, true, false),
                MainActivity.labelFor(false, false, true),
        };
        java.util.Set<String> unique = new java.util.HashSet<>();
        java.util.Collections.addAll(unique, labels);
        assertEquals(4, unique.size());
    }
}
