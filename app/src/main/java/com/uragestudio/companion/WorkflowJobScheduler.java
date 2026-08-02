package com.uragestudio.companion;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

final class WorkflowJobScheduler {
    private static final AtomicInteger NEXT_ID = new AtomicInteger((int) (System.currentTimeMillis() % 100_000) + 30_000);

    static int enqueue(Context context, String kind, String backend, JSONObject options) {
        int id = NEXT_ID.incrementAndGet();
        new WorkflowJobStore(context).create(id, kind, backend, options);
        PersistableBundle extras = new PersistableBundle();
        extras.putInt("workflowJobId", id);
        JobInfo info = new JobInfo.Builder(id, new ComponentName(context, WorkflowJobService.class))
            .setExtras(extras)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build();
        int result = ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(info);
        if (result != JobScheduler.RESULT_SUCCESS) {
            new WorkflowJobStore(context).update(id, "failed", "Android rejected the background job.");
            throw new IllegalStateException("Android could not schedule this workflow.");
        }
        return id;
    }

    static void cancel(Context context, int id) {
        ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(id);
        new WorkflowJobStore(context).update(id, "cancelled", "Cancelled by the user.");
    }

    private WorkflowJobScheduler() {}
}
