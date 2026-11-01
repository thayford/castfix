package com.thayford.castfix;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
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

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
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
        root.addView(text("Lets phones cast to this Chromecast while the setup wizard stays off for your own "
                + "launcher. It runs by itself after every restart; use the button if casting stops anyway. "
                + "If a run is interrupted, the next one puts the setup wizard back first.",
                16, Color.rgb(170, 180, 190)));
        status = text("", 20, Color.WHITE);
        status.setPadding(0, dp(24), 0, 0);
        root.addView(status);
        setup = text("", 14, Color.rgb(170, 180, 190));
        root.addView(setup);

        run = new Button(this);
        run.setText("Fix casting now");
        run.setOnClickListener(v -> start("button"));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(20);
        buttonParams.gravity = Gravity.START;
        root.addView(run, buttonParams);

        recent = text("", 12, Color.rgb(140, 150, 160));
        recent.setTypeface(Typeface.MONOSPACE);
        recent.setPadding(0, dp(20), 0, 0);
        root.addView(recent);
        setContentView(root);
        run.requestFocus();

        // From a computer: am start -n com.thayford.castfix/.MainActivity --ez run true
        if (savedInstanceState == null && getIntent().getBooleanExtra("run", false)) start("adb");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("run", false) && !CastFixService.isRunning()) start("adb");
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(done, new IntentFilter(CastFixService.ACTION_DONE), Context.RECEIVER_NOT_EXPORTED);
        refresh();
        // Opening the app is the moment the owner is looking, so it is also the moment to put the device back
        // if a run died mid-way. This costs one adb round trip and only happens when a run left a record.
        if (!CastFixService.isRunning() && new CastFixer(this).needsRecovery()) start("recovery");
    }

    @Override
    protected void onPause() {
        unregisterReceiver(done);
        super.onPause();
    }

    private void start(String trigger) {
        if (CastFixService.isRunning()) {
            // The service refuses a second concurrent run, so say so instead of claiming to work.
            status.setText("A run is already in progress.");
            return;
        }
        startForegroundService(new Intent(this, CastFixService.class)
                .putExtra(CastFixService.EXTRA_TRIGGER, trigger));
        run.setEnabled(false);
        run.setText("Working, about a minute");
        status.setText("Running…");
    }

    private void refresh() {
        boolean busy = CastFixService.isRunning();
        CastFixer fixer = new CastFixer(this);
        run.setEnabled(!busy);
        run.setText(busy ? "Working, about a minute" : "Fix casting now");
        String last = "Last run: " + log.lastResult();
        String recovery = fixer.recoveryNote();
        status.setText(recovery.isEmpty() ? last : recovery);
        boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        setup.setText("Setup: " + (log.isPaired() ? "paired" : "NOT paired")
                + " · settings permission " + (granted ? "granted" : "MISSING"));
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
