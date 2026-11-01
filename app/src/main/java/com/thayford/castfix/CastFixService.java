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
    /**
     * Held for the length of a run. Claimed before startForeground so a second startForegroundService cannot
     * post a second notification while doing no work.
     */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RUNNING.compareAndSet(false, true)) {
            // A run is already in progress: the notification that is up belongs to it, so this start must
            // not touch it. Release nothing, because the running thread owns the flag.
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Cast Fix", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_fix)
                .setContentTitle("Cast Fix")
                .setContentText("Getting casting ready")
                .setOngoing(true)
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        String trigger = intent == null ? null : intent.getStringExtra(EXTRA_TRIGGER);
        new Thread(() -> {
            String result;
            try {
                result = new CastFixer(this).run(trigger == null ? "unknown" : trigger);
            } finally {
                RUNNING.set(false);
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "Cast Fix: " + result, Toast.LENGTH_LONG).show();
                sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }, "castfix").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
