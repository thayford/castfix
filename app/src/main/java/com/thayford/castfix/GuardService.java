package com.thayford.castfix;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stays running for the two things that need watching all the time: pausing accessibility services while a
 * cast is on screen (CastPause), and the Bluetooth remote watchdog (RemoteWatch). Started at boot, after
 * updates, by the app and by the 15-minute check, so it comes back if Android stops it.
 */
public class GuardService extends Service {
    private static final String CHANNEL = "castfix";
    private static final long CAST_POLL_MS = 1_000;
    private static final long CAST_POLL_ASLEEP_MS = 10_000;
    /** A cast has to be gone this long before the services come back, so brief switches don't flap them. */
    private static final long RESTORE_AFTER_MS = 3_000;
    private static final long REMOTE_POLL_MS = 30_000;
    private static final long REMOTE_CONFIRM_MS = 15_000;
    private static final long RESTART_COOLDOWN_MS = 3 * 60_000L;
    private static final long GRANT_RETRY_MS = 60 * 60_000L;

    private HandlerThread thread;
    private Handler handler;
    private final ExecutorService slow = Executors.newSingleThreadExecutor(r -> new Thread(r, "castfix-guard-adb"));
    private final AtomicBoolean adbBusy = new AtomicBoolean();
    private FixLog log;
    private CastPause pause;
    private SharedPreferences prefs;
    private boolean started;

    // cast detection, on the guard thread only
    private long eventsFrom;
    private long lastResumedAt;
    private boolean castResumedLast;
    private long castStoppedAt;
    private long castGoneSince;
    private boolean castWasOnScreen;
    private long lastPauseTry;
    private boolean noUsageAccessLogged;

    // remote watchdog, on the guard thread only
    private long faultSince;
    private long lastRestart;
    private String lastRemoteState = "";
    private boolean noDumpLogged;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(castTick);
            handler.post(castTick);
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // The remote may be what failed to wake the TV; check now rather than in up to 30 s.
                handler.removeCallbacks(remoteTick);
                handler.post(remoteTick);
            }
        }
    };

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, GuardService.class));
        } catch (IllegalStateException e) {
            // ForegroundServiceStartNotAllowedException while battery optimization is still on
            new FixLog(context).add("guard can't start from the background yet: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log = new FixLog(this);
        pause = new CastPause(this, log);
        prefs = getSharedPreferences("guard", MODE_PRIVATE);
        thread = new HandlerThread("castfix-guard");
        thread.start();
        handler = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, filter, null, handler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Cast Fix", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast_fix)
                .setContentTitle("Cast Fix")
                .setContentText("Watching casts and the remote")
                .setOngoing(true)
                .build();
        startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (!started) {
            started = true;
            log.add("guard started (cast pause: " + CastPause.mode(this) + ", remote watchdog: "
                    + (RemoteWatch.enabled(this) ? "on" : "off") + ")");
            handler.post(castTick);
            handler.post(remoteTick);
            handler.post(this::grantIfNeeded);
        }
        if (intent != null && TestReceiver.ACTION_TEST_RESTART.equals(intent.getAction())) {
            handler.post(() -> {
                log.add("manual test: restarting Bluetooth");
                restartBluetooth("manual test");
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screen);
        handler.removeCallbacksAndMessages(null);
        // Never leave someone's accessibility services off because the guard went away.
        handler.post(() -> pause.restore("guard stopped"));
        thread.quitSafely();
        slow.shutdown();
        super.onDestroy();
    }

    private final Runnable castTick = new Runnable() {
        @Override
        public void run() {
            boolean awake = getSystemService(PowerManager.class).isInteractive();
            try {
                checkCast(awake);
            } catch (RuntimeException e) {
                log.add("cast check failed: " + e);
            }
            handler.postDelayed(this, awake ? CAST_POLL_MS : CAST_POLL_ASLEEP_MS);
        }
    };

    private void checkCast(boolean awake) {
        boolean off = "off".equalsIgnoreCase(CastPause.mode(this));
        if (!SelfAdb.hasUsageAccess(this)) {
            if (!noUsageAccessLogged) {
                log.add("cast pause waits for usage access (granted on the next adb run)");
                noUsageAccessLogged = true;
            }
            if (pause.isPaused()) pause.restore("no usage access");
            return;
        }
        noUsageAccessLogged = false;
        boolean onScreen = awake && castOnScreen();
        long now = SystemClock.elapsedRealtime();
        boolean appeared = onScreen && !castWasOnScreen;
        castWasOnScreen = onScreen;
        if (onScreen && !off) {
            castGoneSince = 0;
            // On arrival, then every 10 s in case a service is switched on mid-cast.
            if (!pause.isPaused() && (appeared || now - lastPauseTry >= 10_000)) {
                lastPauseTry = now;
                pause.pause();
            }
        } else if (pause.isPaused()) {
            if (off) {
                pause.restore("cast pause switched off");
            } else if (castGoneSince == 0) {
                castGoneSince = now;
            } else if (now - castGoneSince >= RESTORE_AFTER_MS) {
                pause.restore(awake ? "cast left the screen" : "screen off");
                castGoneSince = 0;
            }
        }
    }

    /** Whether the Cast receiver's player is the activity in front, from the usage events since the last look. */
    private boolean castOnScreen() {
        long now = System.currentTimeMillis();
        if (eventsFrom == 0) eventsFrom = now - 6 * 60 * 60_000L;
        UsageEvents events = getSystemService(UsageStatsManager.class).queryEvents(eventsFrom - 1_000, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            long at = event.getTimeStamp();
            boolean cast = CastPause.CAST_PACKAGE.equals(event.getPackageName())
                    && CastPause.CAST_ACTIVITY.equals(event.getClassName());
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && at >= lastResumedAt) {
                lastResumedAt = at;
                castResumedLast = cast;
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED && cast && at > castStoppedAt) {
                castStoppedAt = at;
            }
        }
        eventsFrom = now;
        return castResumedLast && castStoppedAt < lastResumedAt;
    }

    private final Runnable remoteTick = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            long next = REMOTE_POLL_MS;
            try {
                next = checkRemote();
            } catch (RuntimeException e) {
                log.add("remote check failed: " + e);
            }
            handler.postDelayed(this, next);
        }
    };

    /** One watchdog check; returns when to check next. */
    private long checkRemote() {
        if (!RemoteWatch.enabled(this) || adbBusy.get()) return REMOTE_POLL_MS;
        if (checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            if (!noDumpLogged) {
                log.add("remote watchdog waits for the DUMP permission (granted on the next adb run)");
                noDumpLogged = true;
            }
            return REMOTE_POLL_MS;
        }
        noDumpLogged = false;
        RemoteWatch.Status status = RemoteWatch.read();
        if (status == null) return REMOTE_POLL_MS;
        String state = status.describe();
        if (!state.equals(lastRemoteState)) {
            log.add("remotes: " + state);
            lastRemoteState = state;
            prefs.edit().putString("remotes", state).apply();
        }
        long now = SystemClock.elapsedRealtime();
        if (!status.fault()) {
            if (faultSince != 0) log.add("remote listening again on its own");
            faultSince = 0;
            return REMOTE_POLL_MS;
        }
        if (faultSince == 0) {
            faultSince = now;
            log.add("remote not being listened for; checking again in " + REMOTE_CONFIRM_MS / 1000 + " s");
            return REMOTE_CONFIRM_MS;
        }
        if (lastRestart != 0 && now - lastRestart < RESTART_COOLDOWN_MS) return REMOTE_CONFIRM_MS;
        lastRestart = now;
        faultSince = 0;
        restartBluetooth(state);
        return REMOTE_POLL_MS;
    }

    private void restartBluetooth(String state) {
        if (!adbBusy.compareAndSet(false, true)) return;
        slow.execute(() -> {
            String result;
            try {
                result = RemoteWatch.restart(this, log);
            } catch (Exception e) {
                result = "Bluetooth restart failed: " + e.getMessage();
            } finally {
                adbBusy.set(false);
            }
            log.add("remote watchdog (" + state + "): " + result);
            prefs.edit().putString("restart", result).putLong("restartAt", System.currentTimeMillis())
                    .putInt("restarts", prefs.getInt("restarts", 0) + 1).apply();
            handler.postDelayed(remoteTick, 10_000);
        });
    }

    /** Once paired, gets the adb-only permissions without a computer (at most hourly). */
    private void grantIfNeeded() {
        if (SelfAdb.hasGrants(this) || !log.isPaired() || CastFixService.isRunning()) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("grantTried", 0) < GRANT_RETRY_MS) return;
        if (!adbBusy.compareAndSet(false, true)) return;
        prefs.edit().putLong("grantTried", now).apply();
        slow.execute(() -> {
            try {
                SelfAdb.run(this, log, 60_000, shell -> null);
            } catch (Exception e) {
                log.add("could not get the permissions over adb: " + e.getMessage());
            } finally {
                adbBusy.set(false);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
