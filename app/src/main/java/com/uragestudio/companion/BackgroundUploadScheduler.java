package com.uragestudio.companion;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.PersistableBundle;

public final class BackgroundUploadScheduler {
    private BackgroundUploadScheduler() {}

    public static int enqueue(Context context, Uri uri, String kind, String fileName, String contentType, long totalSize) {
        int jobId = 10_000 + Math.abs((uri.toString() + kind).hashCode() % 1_000_000);
        PersistableBundle extras = new PersistableBundle();
        extras.putString("uri", uri.toString());
        extras.putString("kind", kind);
        extras.putString("fileName", fileName);
        extras.putString("contentType", contentType == null ? "application/octet-stream" : contentType);
        extras.putLong("totalSize", totalSize);
        JobInfo job = new JobInfo.Builder(jobId, new ComponentName(context, ResumableUploadJobService.class))
            .setRequiredNetwork(new NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
            .setBackoffCriteria(15_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .setExtras(extras)
            .build();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Android could not schedule the upload.");
        return jobId;
    }
}
