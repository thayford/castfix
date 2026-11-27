package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How a shell result is judged. adbd's legacy shell stream merges stderr into stdout and reports its exit
 * status separately, so a refused `pm enable` prints its error as ordinary output and would otherwise look
 * like it worked. An unknown status is not success either: treating it as success is what once let an
 * interrupted `pm enable` look like it had worked.
 */
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
    public void missingMarkerIsUnknownAndNotSuccess() {
        // -1 means the marker never came back. The caller verifies the device state anyway, but this must not
        // be reported as success on its own.
        Shell.Result result = Shell.Result.parse("partial output");
        assertEquals(-1, result.status);
        assertFalse(result.ok());
        assertEquals("partial output", result.output);
    }

    @Test
    public void zeroIsSuccess() {
        assertTrue(new Shell.Result("", 0).ok());
    }

    @Test
    public void outputIsTrimmedToOneLine() {
        Shell.Result result = new Shell.Result("  first line  \nsecond\nthird", 2);
        assertEquals("first line", result.problem().trim());
    }
}
