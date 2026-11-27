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
        if (port <= 0 || port > 65535 || code == null || code.trim().isEmpty()) {
            setResult(1, "usage: --ei port <pairing port> --es code <pairing code> [--es host <tv ip>]", null);
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            FixLog log = new FixLog(context);
            String result = "pairing did not finish";
            try (AdbManager adb = new AdbManager(context)) {
                adb.pair(host == null ? "127.0.0.1" : host, port, code.trim());
                log.setPaired();
                result = "paired";
            } catch (Exception e) {
                result = "pairing failed: " + e;
            } finally {
                // goAsync() keeps the process alive until finish() is called; a throw in between would leave
                // the broadcast unanswered and the owner looking at a hung `am broadcast`.
                log.add(result + " (port " + port + ")");
                // The broadcast data stays exactly "paired" on success: that is the signal the README tells
                // the owner to look for in the `am broadcast` output, so it must not gain a suffix.
                pending.setResult("paired".equals(result) ? 0 : 1, result, null);
                pending.finish();
            }
        }, "castfix-pair").start();
    }
}
