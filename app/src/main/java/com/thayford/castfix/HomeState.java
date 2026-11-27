package com.thayford.castfix;

/**
 * Where Home resolves, read from the package manager's resolver rather than from window focus: on Google TV
 * Streamer (kirkwood) `dumpsys window` prints only `mCurrentFocus=null`, so a focus check never fired there.
 *
 * <p>Measured on kirkwood (UTTK.260317.003) with both packages enabled, the HOME candidates and their
 * priorities are setupwraith/.MainActivity 4 (component-disabled on that build), launcherx 2,
 * setupwraith/.RecoveryActivity 1, replacement launchers 0, FallbackHome -1000. A persisted preferred
 * activity wins over those priorities, which is why a replacement launcher set with set-home-activity keeps
 * Home even while the wizard is briefly on.
 *
 * <p>From PR #1 by Sebastián Valdivia Loyola (svaldivia12).
 */
final class HomeState {
    static final String QUERY =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";

    /** The package/activity Home resolves to, e.g. {@code com.gathin.tvhome/.MainActivity}; "" if none. */
    final String component;

    HomeState(String component) {
        this.component = component == null ? "" : component.trim();
    }

    static HomeState read(Shell shell) throws Exception {
        return parse(shell.run(QUERY));
    }

    /** `resolve-activity --brief` prints a metadata line first and the component last. */
    static HomeState parse(String output) {
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

    /** A launcher the owner chose: neither Google's, nor the wizard's, nor the system's stand-in. */
    boolean ownedByReplacement() {
        if (!found()) return false;
        String pkg = pkg();
        return !pkg.equals(CastFixer.STOCK_HOME)
                && !pkg.equals(CastFixer.SETUP)
                && !pkg.equals("android")
                && !pkg.equals("com.android.internal.app")
                && !pkg.equals("com.android.tv.settings");
    }

    /** The setup wizard answering Home: the state in which kirkwood's system_server crash-loops. */
    boolean ownedByWizard() {
        return found() && pkg().equals(CastFixer.SETUP);
    }

    @Override
    public String toString() {
        return found() ? component : "(none)";
    }
}
