package com.thayford.castfix;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * While an accessibility service such as Key Mapper is on, the Cast receiver's web player builds an
 * accessibility tree for the page, and Disney+'s cast player then keeps its controls (subtitles, scrub
 * bar) on screen after each episode change instead of hiding them after a few seconds. So while a cast
 * is on screen, the services that aren't screen readers are switched off, and switched back on afterwards.
 *
 * `settings put global castfix_cast_a11y off` turns this off; a comma-separated list of components
 * (e.g. io.github.sds100.keymapper/.system.accessibility.MyAccessibilityService) pauses only those.
 * The default, `auto`, pauses every enabled service except those giving spoken or braille feedback.
 */
final class CastPause {
    static final String SETTING = "castfix_cast_a11y";
    static final String CAST_PACKAGE = "com.google.android.apps.mediashell";
    static final String CAST_ACTIVITY = "org.chromium.chromecast.shell.CastWebContentsActivity";
    private static final String ENABLED = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String A11Y_ON = Settings.Secure.ACCESSIBILITY_ENABLED;

    private final Context context;
    private final FixLog log;
    private final SharedPreferences prefs;

    CastPause(Context context, FixLog log) {
        this.context = context;
        this.log = log;
        this.prefs = context.getSharedPreferences("castpause", Context.MODE_PRIVATE);
    }

    static String mode(Context context) {
        String value = Settings.Global.getString(context.getContentResolver(), SETTING);
        return value == null || value.trim().isEmpty() ? "auto" : value.trim();
    }

    boolean isPaused() {
        return !prefs.getString("paused", "").isEmpty();
    }

    /** Switches off the services to pause; returns what it switched off, empty if nothing. */
    List<String> pause() {
        String mode = mode(context);
        if ("off".equalsIgnoreCase(mode)) return new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        List<String> enabled = split(Settings.Secure.getString(resolver, ENABLED));
        List<String> toPause = select(enabled, mode);
        if (toPause.isEmpty()) return toPause;
        List<String> remaining = new ArrayList<>(enabled);
        remaining.removeAll(toPause);
        Set<String> paused = new LinkedHashSet<>(split(prefs.getString("paused", "")));
        paused.addAll(toPause);
        // Saved first, so a crash or restart in between still restores them.
        prefs.edit().putString("paused", String.join(":", paused)).commit();
        Settings.Secure.putString(resolver, ENABLED, String.join(":", remaining));
        if (remaining.isEmpty()) Settings.Secure.putInt(resolver, A11Y_ON, 0);
        log.add("cast on screen: paused " + names(toPause));
        return toPause;
    }

    /** Switches the paused services back on, keeping any the user enabled meanwhile. */
    void restore(String why) {
        List<String> paused = split(prefs.getString("paused", ""));
        if (paused.isEmpty()) return;
        ContentResolver resolver = context.getContentResolver();
        Set<String> merged = new LinkedHashSet<>(split(Settings.Secure.getString(resolver, ENABLED)));
        Set<String> present = new LinkedHashSet<>();
        for (String component : merged) present.add(normalize(component));
        for (String component : paused) {
            if (!present.contains(normalize(component))) merged.add(component);
        }
        Settings.Secure.putString(resolver, ENABLED, String.join(":", merged));
        if (!merged.isEmpty()) Settings.Secure.putInt(resolver, A11Y_ON, 1);
        prefs.edit().remove("paused").commit();
        log.add(why + ": restored " + names(paused));
    }

    private List<String> select(List<String> enabled, String mode) {
        List<String> chosen = new ArrayList<>();
        if ("auto".equalsIgnoreCase(mode)) {
            Map<String, AccessibilityServiceInfo> installed = installed();
            for (String component : enabled) {
                AccessibilityServiceInfo info = installed.get(normalize(component));
                // Unknown services are left alone, as are screen readers: they are how someone uses the TV.
                if (info == null) continue;
                int reader = AccessibilityServiceInfo.FEEDBACK_SPOKEN | AccessibilityServiceInfo.FEEDBACK_BRAILLE;
                if ((info.feedbackType & reader) == 0) chosen.add(component);
            }
        } else {
            Set<String> wanted = new LinkedHashSet<>();
            for (String entry : mode.split("[,\\s]+")) {
                if (!entry.isEmpty()) wanted.add(normalize(entry));
            }
            for (String component : enabled) {
                if (wanted.contains(normalize(component))) chosen.add(component);
            }
        }
        return chosen;
    }

    private Map<String, AccessibilityServiceInfo> installed() {
        Map<String, AccessibilityServiceInfo> map = new HashMap<>();
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
            ServiceInfo service = info.getResolveInfo().serviceInfo;
            map.put(new ComponentName(service.packageName, service.name).flattenToString(), info);
        }
        return map;
    }

    private static String normalize(String component) {
        ComponentName name = ComponentName.unflattenFromString(component.trim());
        return name == null ? component.trim() : name.flattenToString();
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        if (value == null) return parts;
        for (String part : value.split(":")) {
            if (!part.trim().isEmpty()) parts.add(part.trim());
        }
        return parts;
    }

    private static String names(List<String> components) {
        List<String> names = new ArrayList<>();
        for (String component : components) {
            ComponentName name = ComponentName.unflattenFromString(component);
            names.add(name == null ? component : name.getPackageName());
        }
        return String.join(", ", names);
    }
}
