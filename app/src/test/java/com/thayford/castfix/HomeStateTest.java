package com.thayford.castfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Classification of "where Home resolves", using the components measured on Google TV Streamer
 * (kirkwood, UTTK.260317.003).
 */
public class HomeStateTest {

    @Test
    public void replacementLauncherIsRecognised() {
        HomeState home = new HomeState("com.gathin.tvhome/.MainActivity");
        assertTrue(home.found());
        assertTrue(home.ownedByReplacement());
        assertFalse(home.ownedByWizard());
        assertEquals("com.gathin.tvhome", home.pkg());
    }

    @Test
    public void secondReplacementLauncherIsRecognised() {
        assertTrue(new HomeState("com.unbrokensoftware.launchbox/.HomeActivity").ownedByReplacement());
    }

    @Test
    public void stockLauncherIsNotAReplacement() {
        HomeState home = new HomeState(CastFixer.STOCK_HOME + "/.home.HomeActivity");
        assertTrue(home.found());
        assertFalse(home.ownedByReplacement());
        assertFalse(home.ownedByWizard());
    }

    @Test
    public void wizardIsTheCrashLoopState() {
        HomeState home = new HomeState(CastFixer.SETUP + "/.RecoveryActivity");
        assertTrue(home.ownedByWizard());
        assertFalse(home.ownedByReplacement());
    }

    @Test
    public void fallbackHomeIsNotAReplacement() {
        // No launcher is ready: restoreHome must leave the stock launcher on rather than disable it.
        HomeState home = new HomeState("com.android.tv.settings/.system.FallbackHome");
        assertFalse(home.ownedByReplacement());
        assertFalse(home.ownedByWizard());
    }

    @Test
    public void nothingResolvingIsNotAReplacement() {
        HomeState home = new HomeState("");
        assertFalse(home.found());
        assertFalse(home.ownedByReplacement());
        assertFalse(home.ownedByWizard());
    }

    @Test
    public void resolveActivityOutputKeepsTheComponentLine() {
        // `resolve-activity --brief` prints a metadata line first and the component last.
        String output = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"
                + "com.gathin.tvhome/.MainActivity\n";
        String component = "";
        for (String line : output.split("\n")) {
            String value = line.trim();
            if (value.contains("/") && !value.contains(" ")) component = value;
        }
        assertEquals("com.gathin.tvhome/.MainActivity", new HomeState(component).component);
    }
}
