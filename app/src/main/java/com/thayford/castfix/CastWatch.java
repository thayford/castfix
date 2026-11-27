package com.thayford.castfix;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * The Cast receiver restarts itself most nights (seen between 2 and 5 am) while the TV only sleeps, and
 * comes back without its credentials. Every 15 minutes this asks it to sign a challenge and starts the fix
 * when the answer comes back unsigned. Battery optimization has to be off for the app (CastFixer does that)
 * so the check also runs while the TV sleeps and may start the fix from the background.
 */
public class CastWatch extends JobService {
    private static final int JOB_ID = 1;
    /** JobScheduler's shortest period. */
    private static final long PERIOD_MS = 15 * 60_000L;

    /** Schedules the check unless it already is; it then survives restarts by itself. */
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs.getPendingJob(JOB_ID) != null) return;
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, CastWatch.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        GuardService.start(this); // brings the guard back if Android stopped it
        if (CastFixService.isRunning()) return false;
        new Thread(() -> {
            if (CastProbe.signed() == Boolean.FALSE) {
                try {
                    startForegroundService(new Intent(this, CastFixService.class)
                            .putExtra(CastFixService.EXTRA_TRIGGER, "Cast receiver lost its credentials"));
                } catch (IllegalStateException e) {
                    // ForegroundServiceStartNotAllowedException while battery optimization is still on
                    new FixLog(this).add("casting broke, but the fix can't start from the background: " + e);
                }
            }
            jobFinished(params, false);
        }, "castfix-watch").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
