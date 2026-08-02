package com.uragestudio.companion;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ResumableDownloadJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Future<?>> activeJobs = new ConcurrentHashMap<>();

    @Override
    public boolean onStartJob(JobParameters parameters) {
        activeJobs.put(parameters.getJobId(), executor.submit(() -> runDownload(parameters)));
        return true;
    }

    private void runDownload(JobParameters parameters) {
        boolean retry = true;
        try {
            SecurePairingStore.Pairing pairing = new SecurePairingStore(this).load();
            if (pairing == null) throw new IllegalStateException("Pair with a dashboard before downloading.");
            DashboardApi api = new DashboardApi(pairing.baseUrl(), pairing.token(), pairing.certificateSha256());
            String downloadUrl = parameters.getExtras().getString("downloadUrl", "");
            String fileName = parameters.getExtras().getString("fileName", "download.bin");
            File directory = new File(getCacheDir(), "resumable-downloads");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create the download cache.");
            File partial = new File(directory, parameters.getJobId() + ".part");
            long offset = partial.exists() ? partial.length() : 0;
            HttpURLConnection connection = api.openDownload(downloadUrl, offset);
            int status = connection.getResponseCode();
            if (status == 416 && rangeTotal(connection.getHeaderField("Content-Range")) == offset && offset > 0) {
                AndroidMediaStore.saveDownload(getContentResolver(), fileName, "application/octet-stream", partial);
                if (!partial.delete()) partial.deleteOnExit();
                retry = false;
                return;
            }
            if (status != 200 && status != 206) throw new IllegalStateException("Dashboard download failed with HTTP " + status + ".");
            boolean resumed = status == 206 && offset > 0;
            if (!resumed) offset = 0;
            try (RandomAccessFile output = new RandomAccessFile(partial, "rw"); InputStream input = connection.getInputStream()) {
                if (resumed) output.seek(offset);
                else output.setLength(0);
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Download paused by Android.");
                    if (count > 0) output.write(buffer, 0, count);
                }
            }
            AndroidMediaStore.saveDownload(getContentResolver(), fileName,
                connection.getContentType() == null ? "application/octet-stream" : connection.getContentType(), partial);
            if (!partial.delete()) partial.deleteOnExit();
            retry = false;
        } catch (Exception ignored) {
            // The partial file remains in app storage; JobScheduler retries using an HTTP Range request.
        } finally {
            activeJobs.remove(parameters.getJobId());
            jobFinished(parameters, retry);
        }
    }

    private long rangeTotal(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        Future<?> job = activeJobs.remove(parameters.getJobId());
        if (job != null) job.cancel(true);
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
