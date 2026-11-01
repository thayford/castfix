package com.thayford.castfix;

/**
 * Where Home resolves, and what a run should put back when it is done.
 *
 * <p>Measured on Google TV Streamer (kirkwood, UTTK.260317.003) with both packages enabled: the HOME
 * candidates and their intent-filter priorities are
 *
 * <pre>
 *   setupwraith/.MainActivity          priority 4   (component-disabled on this build)
 *   launcherx/.home.HomeActivity        priority 2
 *   setupwraith/.RecoveryActivity       priority 1
 *   tvhome, launchbox                   priority 0
 *   FallbackHome                        priority -1000
 * </pre>
 *
 * <p>AOSP resolves a persisted preferred activity before it looks at those priorities
 * (PreferredActivityHelper.findPreferredActivityNotLocked, then updateDefaultHomeNotLocked), which is why
 * the replacement launcher keeps Home here even with the wizard enabled: the preferred activity is pinned to
 * it with mAlways=true. When nothing is pinned, the highest priority wins, so enabling launcherx before the
 * wizard does help on this device. That is a belt-and-braces ordering, not a guarantee: on builds where
 * setupwraith's MainActivity is not component-disabled it outranks everything at priority 4. The protection
 * that actually holds is never leaving the wizard enabled; see {@link RunJournal}.
 */
final class HomeState {

    private static final String HOME_QUERY =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";

    /** The package/activity Home resolves to now, e.g. {@code com.gathin.tvhome/.MainActivity}. */
    final String component;

    HomeState(String component) {
        this.component = component == null ? "" : component.trim();
    }

    /** Reads where Home resolves, or an empty component when nothing answers. */
    static HomeState read(Shell shell) throws Exception {
        String output = shell.run(HOME_QUERY);
        // resolve-activity --brief prints its metadata line first and the component last; a component line
        // is the only one with a slash in it.
        String component = "";
        for (String line : output.split("\n")) {
            String value = line.trim();
            if (value.contains("/") && !value.contains(" ")) component = value;
        }
        return new HomeState(component);
    }

    boolean found() {
        return !component.isEmpty();
    }

    String pkg() {
        int slash = component.indexOf('/');
        return slash < 0 ? component : component.substring(0, slash);
    }

    /** True when the replacement launcher owns Home: a real component that is neither Google's nor the wizard's. */
    boolean ownedByReplacement() {
        if (!found()) return false;
        String pkg = pkg();
        return !pkg.equals(CastFixer.STOCK_HOME)
                && !pkg.equals(CastFixer.SETUP)
                && !pkg.equals("android")
                // The system's own stand-in while no launcher is ready; not a launcher anyone chose.
                && !pkg.equals("com.android.internal.app")
                && !pkg.equals("com.android.tv.settings");
    }

    /** True when the setup wizard is what a Home press would reach, which is the crash-loop state. */
    boolean ownedByWizard() {
        return found() && pkg().equals(CastFixer.SETUP);
    }
}
