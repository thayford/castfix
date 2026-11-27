package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs the fix after every restart; BOOT_COMPLETED is one of the few moments a background app may start a
 * service. Also makes sure CastWatch is scheduled and the guard is running, including after the app is updated.
 *
 * <p>An update replaces the running process without a reboot, so MY_PACKAGE_REPLACED starts a run too;
 * otherwise casting would stay broken until the next restart.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        CastWatch.schedule(context);
        GuardService.start(context);
        // BOOT_COMPLETED arrives before the network is necessarily up, and this app cannot do anything
        // without it. CastFixService retries once a minute later, and MainActivity also retries from there;
        // this listener just gets the run started as early as it can.
        context.startForegroundService(new Intent(context, CastFixService.class)
                .putExtra(CastFixService.EXTRA_TRIGGER, "restart"));
    }
}
