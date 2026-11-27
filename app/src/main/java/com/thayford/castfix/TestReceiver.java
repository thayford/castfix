package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * From a computer, restarts Bluetooth the way the remote watchdog would, to check that it works:
 * adb shell am broadcast -n com.thayford.castfix/.TestReceiver -a com.thayford.castfix.TEST_REMOTE_RESTART
 * Requires DUMP, like PairReceiver, so only the adb shell can send it.
 */
public class TestReceiver extends BroadcastReceiver {
    static final String ACTION_TEST_RESTART = "com.thayford.castfix.TEST_REMOTE_RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_TEST_RESTART.equals(intent.getAction())) return;
        context.startForegroundService(new Intent(context, GuardService.class).setAction(ACTION_TEST_RESTART));
        setResult(0, "restarting Bluetooth; see the log (logcat -s CastFix)", null);
    }
}
