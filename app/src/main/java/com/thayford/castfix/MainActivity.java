package com.thayford.castfix;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.format.DateFormat;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Status, the last runs, and a button to run the fix by hand (e.g. if casting stops without a restart). */
public class MainActivity extends Activity {
    private FixLog log;
    private TextView status;
    private TextView setup;
    private TextView recent;
    private Button run;

    /**
     * What the last signing check found: {@link CastProbe#signed()} (TRUE signed, FALSE unsigned, null no
     * answer). A cached result is not evidence: the owner can enable the wizard, or the receiver can be
     * replaced, without any run of this app happening - so the screen asks the receiver itself each time it
     * comes to the foreground.
     */
    private Boolean probed = null;
    /** True once a check has finished, so null above can mean "no answer" rather than "not asked yet". */
    private boolean probedReturned = false;
    /**
     * Whether the setup wizard was enabled at the same moment, which the handshake cannot tell. Null means
     * the package manager could not answer, which is not the same as "off": the screen must not claim a TV is
     * healthy on a reading it never got.
     */
    private Boolean probedWizardOn = null;
    /** How long the screen waits for the handshake; it runs on every resume, so it must not stall the UI. */
    private static final int UI_PROBE_TIMEOUT_MS = 3_000;
    /** True while the check is in flight, so the screen says so instead of showing a stale verdict. */
    private volatile boolean probing = false;
    /** Set when a probe completes after the activity is gone, so the next resume re-checks. */
    private volatile boolean probeStale = true;
    /**
     * Set between asking for a run and the service reporting that it started. Without it, a refresh landing
     * in that gap sees no run, overwrites "Fixing casting…" with the previous result, and re-probes a device
     * that is about to change under it. Bounded by {@link #START_GRACE_MS} so a service that never starts
     * cannot leave the screen stuck on "Fixing casting…" forever.
     */
    private long startingAt = 0;
    /** How long to treat a requested run as running before deciding it never started. */
    private static final long START_GRACE_MS = 20_000;
    /** Guards the receiver registration, which throws if it happens twice without an unregister. */
    private boolean registered = false;

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // A fix just changed the device, so whatever was probed before is out of date.
            startingAt = 0;
            probeStale = true;
            refresh();
            if (!CastFixService.isRunning()) probe();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log = new FixLog(this);
        int pad = dp(48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(12, 18, 24));

        root.addView(text("Cast Fix", 34, Color.WHITE));
        root.addView(text("Lets phones cast to this Chromecast or Google TV Streamer while the setup wizard stays off for your own "
                + "launcher. It runs by itself after every restart and whenever the Cast receiver loses its "
                + "credentials (checked every 15 minutes); use the button if casting stops anyway. While a "
                + "cast is on screen it pauses accessibility services such as Key Mapper (they keep Disney+'s "
                + "player controls from hiding), and it restarts Bluetooth when a paired remote can no longer "
                + "reconnect.",
                16, Color.rgb(170, 180, 190)));
        status = text("", 20, Color.WHITE);
        status.setPadding(0, dp(24), 0, 0);
        root.addView(status);
        setup = text("", 14, Color.rgb(170, 180, 190));
        root.addView(setup);

        // The label tracks the screen's state, and only one of those states is pressable, so a greyed button
        // with "Fixed" on it is the whole answer to "is there anything for me to do?".
        run = new Button(this);
        run.setText(LABEL_ACTION);
        run.setOnClickListener(v -> start("button"));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(20);
        buttonParams.gravity = Gravity.START;
        root.addView(run, buttonParams);

        TextView heading = text("Recent runs", 12, Color.rgb(120, 130, 140));
        heading.setPadding(0, dp(24), 0, 0);
        root.addView(heading);
        recent = text("", 12, Color.rgb(140, 150, 160));
        recent.setTypeface(Typeface.MONOSPACE);
        recent.setPadding(0, dp(6), 0, 0);
        root.addView(recent);
        setContentView(root);
        run.requestFocus();
        CastWatch.schedule(this);
        GuardService.start(this);

        // From a computer: am start -n com.thayford.castfix/.MainActivity --ez run true
        if (savedInstanceState == null && getIntent().getBooleanExtra("run", false)) start("adb");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // An intent delivered to an activity that is already visible never reaches onCreate, so this is the
        // only path that makes `am start ... --ez run true` work while the app is on screen - which is exactly
        // when someone debugging would use it.
        if (intent.getBooleanExtra("run", false) && !CastFixService.isRunning()) start("adb");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!registered) {
            registerReceiver(done, new IntentFilter(CastFixService.ACTION_DONE), Context.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        refresh();
        // Opening the app is the moment the owner is looking, so it is also the moment to put the device back
        // if a run died mid-way. This costs one adb round trip and only happens when a run left a record.
        if (!CastFixService.isRunning() && new CastFixer(this).needsRecovery()) {
            start("recovery");
            return;
        }
        probe();
    }

    /**
     * Asks the receiver whether it can sign, off the main thread. This is the only check that describes the
     * device now rather than what the last run recorded, and it is what decides whether the button is usable.
     */
    private void probe() {
        if (probing || CastFixService.isRunning()) return;
        // A result taken before this resume is only trusted if nothing has changed since, which the flag
        // tracks: set on start, cleared by every completed probe.
        if (probedReturned && !probeStale) {
            refresh();
            return;
        }
        probing = true;
        probeStale = false;
        refresh();
        new Thread(() -> {
            Boolean found = null;
            Boolean wizardOn = null;
            try {
                found = CastProbe.signed(UI_PROBE_TIMEOUT_MS);
                // Signing is only half the answer. On this build the setup wizard being enabled is the
                // crash-loop precondition, and it is not visible in the handshake at all, so it has to be
                // asked about separately or the screen would call the device healthy while it is at risk.
                wizardOn = CastFixer.setupEnabled(this);
            } catch (RuntimeException e) {
                // An unchecked failure here must still reach the screen. Otherwise `probing` stays cleared
                // but no refresh ever runs, and the button sits disabled on "Checking casting…" for good.
                log.add("probe failed: " + e);
            } finally {
                probing = false;
            }
            final Boolean result = found;
            final Boolean wizard = wizardOn;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    // The activity went away while the check ran; mark it stale so the next resume re-checks
                    // instead of showing a verdict the owner never saw applied.
                    probeStale = true;
                    return;
                }
                probed = result;
                probedReturned = true;
                probedWizardOn = wizard;
                refresh();
            });
        }, "castfix-probe").start();
    }

    @Override
    protected void onPause() {
        if (registered) {
            unregisterReceiver(done);
            registered = false;
        }
        // The device can change while the screen is away (the owner enables the wizard, the receiver dies,
        // a run from the 15-minute check happens), so the verdict from before is not evidence on the way
        // back. Without this, the screen would keep showing "Casting works" until the activity is recreated.
        probeStale = true;
        super.onPause();
    }

    /** True while a run was requested but the service has not visibly taken over yet. */
    private boolean starting() {
        return startingAt > 0 && SystemClock.elapsedRealtime() - startingAt < START_GRACE_MS;
    }

    /** The one enabled state: nothing is running or being checked, and the device is not provably fine. */
    static final String LABEL_ACTION = "Fix casting now";
    /** A check is in flight; a press now would race the answer. */
    static final String LABEL_CHECKING = "Checking…";
    /** A run is in progress. */
    static final String LABEL_FIXING = "Fixing…";
    /** Confirmed working: signing, and the setup wizard off. */
    static final String LABEL_FIXED = "Fixed";

    /**
     * The label the button shows, given the three states the screen already knows.
     *
     * <p>{@code busy} is tested first: a run can begin while a check is still in flight (an adb intent does
     * not wait for the probe), and "Fixing…" describes what is actually happening then. {@code probing} comes
     * next and still outranks {@code healthy}, which is the point of it: while the answer is unknown, the
     * previous verdict must not be shown as if it were current.
     */
    static String labelFor(boolean probing, boolean busy, boolean healthy) {
        if (busy) return LABEL_FIXING;
        if (probing) return LABEL_CHECKING;
        if (healthy) return LABEL_FIXED;
        return LABEL_ACTION;
    }

    /** True only for the state where running the fix would actually do something. */
    static boolean enabledFor(boolean probing, boolean busy, boolean healthy) {
        return !busy && !probing && !healthy;
    }

    /** Sets the button's label and its enabled state together, so the two cannot drift apart. */
    private void showButton(String label, boolean enabled) {
        run.setText(label);
        run.setEnabled(enabled);
    }

    private void start(String trigger) {
        if (CastFixService.isRunning() || starting()) {
            // The service refuses a second concurrent run, so say so instead of claiming to work.
            status.setText("A fix is already running.");
            return;
        }
        startingAt = SystemClock.elapsedRealtime();
        // Claim the button before the service reports back, so a refresh in the gap cannot re-enable it.
        showButton(LABEL_FIXING, false);
        status.setText("Fixing casting… this takes up to three minutes.");
        try {
            startForegroundService(new Intent(this, CastFixService.class)
                    .putExtra(CastFixService.EXTRA_TRIGGER, trigger));
        } catch (Exception e) {
            // Background-start limits can refuse the service. Say so, and put the action label back rather
            // than leaving "Fixing…" on a button whose run never began.
            startingAt = 0;
            status.setText("Could not start the fix. Open the app and try again.");
            showButton(LABEL_ACTION, true);
        }
    }

    private void refresh() {
        boolean busy = starting() || CastFixService.isRunning();
        CastFixer fixer = new CastFixer(this);
        // "Casting works" is now something the app just checked, not something it remembers from its last run.
        // Both halves matter: the receiver must be signing, and the setup wizard must be provably off, because
        // an enabled wizard is the crash-loop precondition on this build and does not show up in the
        // handshake. An unreadable wizard state is not proof of anything, so it is not healthy either.
        boolean wizardOff = Boolean.FALSE.equals(probedWizardOn);
        boolean wizardOn = Boolean.TRUE.equals(probedWizardOn);
        boolean healthy = !busy && !probing && probed == Boolean.TRUE && wizardOff;
        // The label and the enabled flag are decided together, from the same three booleans, so the button
        // can never say one thing while behaving as another. Only the "needs fixing" state is pressable.
        showButton(labelFor(probing, busy, healthy), enabledFor(probing, busy, healthy));
        String recovery = fixer.recoveryNote();
        if (!recovery.isEmpty()) {
            status.setText(recovery);
        } else if (busy) {
            status.setText("Fixing casting… this takes up to three minutes.");
        } else if (probing) {
            status.setText("Checking casting…");
        } else if (healthy) {
            status.setText("Casting works. Nothing to do.");
        } else if (wizardOn) {
            // The wizard being on is not by itself the crash loop: on a stock setup it stays on and casting
            // works (CastFixer). It is when it owns Home, which is the case this app exists for, that
            // system_server crash-loops. So the warning names the condition instead of asserting the state.
            status.setText("The setup wizard is on. With your own launcher that crash-loops this TV; "
                    + "run the fix to switch it off.");
        } else if (probedReturned && probedWizardOn == null) {
            // The package manager would not answer, so the screen cannot say whether the TV is at risk.
            // Saying either "works" or "crash-loops" here would be a claim it has no evidence for.
            status.setText("Could not read the setup wizard's state. Run the fix to check it.");
        } else if (probedReturned && probed == null) {
            status.setText("The Cast receiver is not answering. Restart the TV.");
        } else if (probed == Boolean.FALSE) {
            status.setText("Casting needs fixing.");
        } else {
            // Nothing has been checked yet on this resume.
            status.setText("Checking casting…");
        }
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        SharedPreferences guard = getSharedPreferences("guard", MODE_PRIVATE);
        long restartAt = guard.getLong("restartAt", 0);
        String detail = "Setup: " + (log.isPaired() ? "paired" : "NOT paired")
                + " · settings permission " + (granted ? "granted" : "MISSING")
                + " · adb-only permissions " + (SelfAdb.hasGrants(this) ? "granted" : "pending (next adb run)")
                + "\nPause accessibility while casting: " + CastPause.mode(this)
                + " · Remote watchdog: " + (RemoteWatch.enabled(this)
                        ? guard.getString("remotes", "starting") + (restartAt == 0 ? ", no restarts yet"
                                : ", " + guard.getInt("restarts", 0) + " restart(s), last "
                                + DateFormat.format("EEE d MMM HH:mm", restartAt))
                        : "off");
        // What the last run concluded is only worth showing while there is something to do about it.
        if (!healthy) detail += "\n" + log.lastRunSummary();
        setup.setText(detail);
        recent.setText(String.join("\n", log.tail(14)));
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
