package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exit status read back from `command; echo __castfix_rc=$?`. */
public class ShellResultTest {

    @Test
    public void successKeepsTheOutput() {
        Shell.Result result = Shell.Result.parse("Package com.example new state: enabled\n__castfix_rc=0\n");
        assertTrue(result.ok());
        assertEquals(0, result.status);
        assertEquals("Package com.example new state: enabled\n", result.output);
    }

    @Test
    public void refusedCommandIsReported() {
        Shell.Result result = Shell.Result.parse(
                "Exception occurred while executing 'enable':\njava.lang.IllegalArgumentException: Unknown package\n"
                        + "__castfix_rc=255\n");
        assertFalse(result.ok());
        assertEquals(255, result.status);
        assertEquals("Exception occurred while executing 'enable':", result.problem());
    }

    @Test
    public void silentFailureShowsItsStatus() {
        Shell.Result result = Shell.Result.parse("__castfix_rc=1");
        assertFalse(result.ok());
        assertEquals("exit 1", result.problem());
    }

    @Test
    public void missingMarkerCountsAsUnknown() {
        Shell.Result result = Shell.Result.parse("partial output");
        assertEquals(-1, result.status);
        assertTrue(result.ok());
        assertEquals("partial output", result.output);
    }
}
