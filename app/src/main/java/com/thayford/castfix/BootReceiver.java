package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs the fix after every restart; BOOT_COMPLETED is one of the few moments a background app may start a service. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        context.startForegroundService(new Intent(context, CastFixService.class)
                .putExtra(CastFixService.EXTRA_TRIGGER, "restart"));
    }
}
