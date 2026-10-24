package io.github.agungksidik.football;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Keeps the stored matches fresh for {@link MatchProvider} (a Home ticker) while the page is closed: every 30
 * minutes while the TV is on a network. The page refreshes on its own while it is open.
 */
public class MatchJob extends JobService {
    static final int ID = 1;
    private static final long PERIOD_MS = 30 * 60_000L;

    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null || jobs.getPendingJob(ID) != null) return;
        jobs.schedule(new JobInfo.Builder(ID, new ComponentName(context, MatchJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> jobFinished(params, !Sync.run(getApplicationContext())), "match-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
