package com.uragestudio.companion;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public final class ResumableUploadJobService extends JobService {
    private static final int CHUNK_BYTES = 1024 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Future<?>> activeJobs = new ConcurrentHashMap<>();

    @Override
    public boolean onStartJob(JobParameters parameters) {
        activeJobs.put(parameters.getJobId(), executor.submit(() -> runUpload(parameters)));
        return true;
    }

    private void runUpload(JobParameters parameters) {
        boolean retry = true;
        try {
            SecurePairingStore.Pairing pairing = new SecurePairingStore(this).load();
            if (pairing == null) throw new IllegalStateException("Pair with a dashboard before uploading.");
            DashboardApi api = new DashboardApi(pairing.baseUrl(), pairing.token(), pairing.certificateSha256());
            Uri uri = Uri.parse(parameters.getExtras().getString("uri", ""));
            String kind = parameters.getExtras().getString("kind", "image");
            String fileName = parameters.getExtras().getString("fileName", "upload.bin");
            String contentType = parameters.getExtras().getString("contentType", "application/octet-stream");
            long totalSize = parameters.getExtras().getLong("totalSize", -1);
            if (totalSize <= 0) throw new IllegalStateException("Selected upload has no readable size.");

            SharedPreferences sessions = getSharedPreferences("resumable_uploads", MODE_PRIVATE);
            String sessionKey = "job-" + parameters.getJobId();
            String uploadId = sessions.getString(sessionKey, "");
            DashboardApi.UploadSession session;
            if (uploadId.isEmpty()) {
                session = api.createUpload(kind, fileName, contentType, totalSize);
                uploadId = session.id();
                sessions.edit().putString(sessionKey, uploadId).apply();
            } else {
                session = api.uploadStatus(uploadId);
            }

            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Selected upload is no longer readable.");
                skipFully(input, session.offset());
                byte[] buffer = new byte[CHUNK_BYTES];
                long offset = session.offset();
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Upload paused by Android.");
                    if (count == 0) continue;
                    session = api.uploadChunk(uploadId, offset, buffer, count);
                    offset = session.offset();
                }
            }
            api.completeUpload(uploadId);
            sessions.edit().remove(sessionKey).apply();
            retry = false;
        } catch (Exception ignored) {
            // JobScheduler retries with exponential backoff while the session retains its acknowledged offset.
        } finally {
            activeJobs.remove(parameters.getJobId());
            jobFinished(parameters, retry);
        }
    }

    private void skipFully(InputStream input, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() >= 0) {
                remaining -= 1;
            } else {
                throw new IllegalStateException("Upload source is shorter than the acknowledged server offset.");
            }
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
