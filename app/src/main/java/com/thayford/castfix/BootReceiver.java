package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs the fix after every restart; BOOT_COMPLETED is one of the few moments a background app may start a
 * service. Also makes sure CastWatch is scheduled and the guard is running, including after the app is updated.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        CastWatch.schedule(context);
        GuardService.start(context);
        if (boot) {
            context.startForegroundService(new Intent(context, CastFixService.class)
                    .putExtra(CastFixService.EXTRA_TRIGGER, "restart"));
        }
    }
}
