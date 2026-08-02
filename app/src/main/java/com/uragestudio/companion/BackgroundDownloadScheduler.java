package com.uragestudio.companion;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkRequest;
import android.os.PersistableBundle;

public final class BackgroundDownloadScheduler {
    private BackgroundDownloadScheduler() {}

    public static int enqueue(Context context, DashboardApi api, MediaItem item) {
        int jobId = 20_000 + Math.abs((item.downloadUrl() + item.id()).hashCode() % 1_000_000);
        PersistableBundle extras = new PersistableBundle();
        extras.putString("downloadUrl", item.downloadUrl());
        extras.putString("fileName", safeName(item.fileName()));
        JobInfo job = new JobInfo.Builder(jobId, new ComponentName(context, ResumableDownloadJobService.class))
            .setRequiredNetwork(new NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
            .setBackoffCriteria(15_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .setExtras(extras)
            .build();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Android could not schedule the download.");
        return jobId;
    }

    private static String safeName(String value) {
        String name = value == null ? "download.bin" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "download.bin" : name;
    }
}
