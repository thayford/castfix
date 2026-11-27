package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * How a line joins the run log.
 *
 * <p>The log is written by several instances at once (the guard lives for hours, a run and the screen come
 * and go), and each one rewrites the whole file. An earlier version kept a snapshot in memory and wrote that
 * instead of what was on disk, which silently deleted every line another instance had appended: a run's own
 * lines vanished behind the guard's chatter. These tests fix the property that failed - a line already in the
 * log belongs to somebody else and must survive.
 */
public class FixLogAppendTest {

    @Test
    public void newLineGoesAtTheEndAndKeepsTheEarlierOnes() {
        List<String> existing = Arrays.asList("old one", "old two");
        List<String> result = FixLog.append(existing, "new", 300);

        assertEquals(Arrays.asList("old one", "old two", "new"), result);
    }

    @Test
    public void linesFromAnotherInstanceAreNotDropped() {
        // The bug: a stale snapshot meant another writer's lines disappeared. Anything present when this
        // instance appends has to still be there afterwards.
        List<String> fromOtherWriter = Arrays.asList("03:00 guard: remotes: armed", "03:01 guard: idle");
        List<String> result = FixLog.append(fromOtherWriter, "03:02 --- run (button)", 300);

        assertTrue(result.contains("03:00 guard: remotes: armed"));
        assertTrue(result.contains("03:01 guard: idle"));
        assertTrue(result.contains("03:02 --- run (button)"));
        assertEquals(3, result.size());
    }

    @Test
    public void fullLogDropsTheOldestNotTheNewest() {
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < 5; i++) existing.add("line " + i);

        List<String> result = FixLog.append(existing, "newest", 5);

        assertEquals(5, result.size());
        assertEquals("newest", result.get(4));
        // line 0 is the one that had to go; every newer line and the new one stay.
        assertEquals(Arrays.asList("line 1", "line 2", "line 3", "line 4", "newest"), result);
    }

    @Test
    public void theNewLineIsNeverTheOneTrimmed() {
        // A full log must make room first; trimming after adding would drop what was just written.
        List<String> existing = Arrays.asList("a", "b");
        assertEquals(2, FixLog.append(existing, "c", 2).size());
        assertTrue(FixLog.append(existing, "c", 2).contains("c"));
    }

    @Test
    public void emptyLogTakesTheFirstLine() {
        assertEquals(Collections.singletonList("first"), FixLog.append(Collections.emptyList(), "first", 300));
    }

    @Test
    public void theInputListIsNotModified() {
        // callers hand in the list they just read from disk; mutating it would be a surprise.
        List<String> existing = new ArrayList<>(Arrays.asList("a", "b"));
        FixLog.append(existing, "c", 300);
        assertEquals(Arrays.asList("a", "b"), existing);
    }
}
