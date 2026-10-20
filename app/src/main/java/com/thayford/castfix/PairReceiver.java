package com.thayford.castfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Pairs the app's adb key with Wireless debugging. The TV can't show its pairing code and this app at the same
 * time, so the code comes from a computer while the pairing dialog stays open (README, Setup). The result shows
 * in the `am broadcast` output.
 */
public class PairReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int port = intent.getIntExtra("port", -1);
        String code = intent.getStringExtra("code");
        String host = intent.getStringExtra("host");
        if (port <= 0 || code == null) {
            setResult(1, "usage: --ei port <pairing port> --es code <pairing code> [--es host <tv ip>]", null);
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            FixLog log = new FixLog(context);
            String result;
            try (AdbManager adb = new AdbManager(context)) {
                adb.pair(host == null ? "127.0.0.1" : host, port, code.trim());
                log.setPaired();
                result = "paired";
            } catch (Exception e) {
                result = "pairing failed: " + e;
            }
            log.add(result + " (port " + port + ")");
            pending.setResult("paired".equals(result) ? 0 : 1, result, null);
            pending.finish();
        }, "castfix-pair").start();
    }
}
