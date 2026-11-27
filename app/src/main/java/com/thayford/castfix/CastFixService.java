package com.thayford.castfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runs CastFixer off the main thread; a foreground service so a run started at boot is not cut short. */
public class CastFixService extends Service {
    static final String EXTRA_TRIGGER = "trigger";
    static final String ACTION_DONE = "com.thayford.castfix.DONE";
    private static final String CHANNEL = "castfix";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Wait before the single boot-run retry, so it is not racing the same failing conditions. */
    private static final long RETRY_AFTER_MS = 60_000;
    /**
     * The startId of the run that owns the flag, or 0. Written and read on the main thread only: onStartCommand
     * and the finish() it posts both run there.
     */
    private int currentStartId = 0;

    static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Cast Fix", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_fix)
                .setContentTitle("Cast Fix")
                .setContentText("Getting casting ready")
                .setOngoing(true)
                .build();
        // Posted before the claim, and for every start: startForegroundService() obliges this service to call
        // startForeground() even when the start is refused below, or Android kills it for not starting in
        // time. The same notification id means a duplicate start updates the existing notification rather
        // than posting a second one, so there is nothing to gain by claiming first.
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (!RUNNING.compareAndSet(false, true)) {
            // A run is in progress and its thread owns the flag, so this start must not release it or call
            // stopSelfResult(): that would stop the service while that run is still going. The running
            // thread's finish() takes the service down.
            return START_NOT_STICKY;
        }
        currentStartId = startId;
        int owner = startId;
        String trigger = intent == null ? null : intent.getStringExtra(EXTRA_TRIGGER);
        new Thread(() -> {
            String result;
            try {
                result = new CastFixer(this).run(trigger == null ? "unknown" : trigger);
                // A boot run can lose to the network. If the credentials are still not there, one retry a
                // minute later is far cheaper than leaving the TV broken until someone opens the app.
                if (trigger != null && trigger.equals("restart") && CastFixer.transientFailure(result)) {
                    result = retryOnce();
                }
            } catch (Throwable t) {
                // run() reports its own failures, so anything reaching here is a bug. Still end the run
                // properly: leaving the foreground notification up forever is worse than a vague message.
                result = "The fix stopped unexpectedly: " + t;
            } finally {
                RUNNING.set(false);
            }
            String shown = result;
            new Handler(Looper.getMainLooper()).post(() -> finish(shown, owner));
        }, "castfix").start();
        return START_NOT_STICKY;
    }

    /** One more attempt after a pause, for the case where the boot run raced the network. */
    private String retryOnce() {
        try {
            Thread.sleep(RETRY_AFTER_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted before retrying.";
        }
        return new CastFixer(this).run("restart-retry");
    }

    /**
     * Whether a finishing run may take the service down.
     *
     * <p>Pure so the rule can be tested without Android. It matters because getting it wrong is invisible:
     * the wrong answer looks like a run that "just didn't work", with nothing in the log to say why.
     *
     * @param finishingStartId the startId of the run that has just finished
     * @param currentStartId   the startId of the run that owns the service now, or 0
     * @param running          whether a run currently holds the flag
     */
    static boolean ownsService(int finishingStartId, int currentStartId, boolean running) {
        // A newer start claimed the flag, so this run's teardown would pull the service out from under it.
        return !running && finishingStartId == currentStartId;
    }

    /**
     * Takes the notification down and tells the screen, whatever the run did. Runs on the main thread.
     *
     * <p>The toast and the broadcast describe what this run did, so they always happen. Only the teardown is
     * conditional: a run that finished after another one took over must not stop the service or remove a
     * notification that now belongs to the newer run.
     */
    private void finish(String result, int startId) {
        Toast.makeText(this, "Cast Fix: " + result, Toast.LENGTH_LONG).show();
        sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()));
        // stopSelf(startId) already refuses when a later start exists; the guard also skips stopForeground,
        // which would otherwise leave the newer run alive but without the notification keeping it so.
        if (ownsService(startId, currentStartId, RUNNING.get())) {
            currentStartId = 0;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
