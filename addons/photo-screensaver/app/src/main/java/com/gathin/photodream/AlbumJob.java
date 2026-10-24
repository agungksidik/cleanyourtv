package com.gathin.photodream;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Keeps today's album pictures ready before anyone opens the slideshow: every few hours while the TV is on a
 * network, AlbumSync does its once-a-day work (and nothing on the other runs). Scheduled once album.txt is set.
 */
public class AlbumJob extends JobService {
    static final int ID = 1;
    private static final long PERIOD_MS = 3 * 60 * 60_000L;

    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null || jobs.getPendingJob(ID) != null) return;
        jobs.schedule(new JobInfo.Builder(ID, new ComponentName(context, AlbumJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> jobFinished(params, !AlbumSync.syncIfDue(getApplicationContext())), "album-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
