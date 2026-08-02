package com.uragestudio.companion;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.net.Uri;
import android.os.Build;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;

public final class WorkflowJobService extends JobService {
    private static final String CHANNEL_ID = "workflow-jobs";
    // Matrix SDK state is a single persistent device store; serialize work to avoid
    // competing sync writers and to keep GPU-heavy dashboard generations orderly.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Future<?>> active = new ConcurrentHashMap<>();

    @Override public boolean onStartJob(JobParameters parameters) {
        active.put(parameters.getJobId(), executor.submit(() -> run(parameters)));
        return true;
    }

    private void run(JobParameters parameters) {
        int id = parameters.getExtras().getInt("workflowJobId", parameters.getJobId());
        WorkflowJobStore store = new WorkflowJobStore(this);
        boolean retry = false;
        try {
            WorkflowJobStore.Job job = store.get(id);
            if (job == null) throw new IllegalStateException("Workflow job data is missing.");
            progress(store, id, "running", "Generating " + job.kind() + "…");
            DashboardApi.WorkflowItem item = generate(job);
            ensureNotCancelled(store, id);
            MediaItem result;
            if ("matrix".equals(job.backend())) {
                progress(store, id, "downloading", "Decrypting and saving the Matrix result…");
                SecureMatrixRelayStore.Config config = new SecureMatrixRelayStore(this).load();
                if (config == null) throw new IllegalStateException("Matrix relay configuration is missing.");
                MatrixSdkRelayClient relay = new MatrixSdkRelayClient(this, config);
                result = new MatrixMediaGalleryStore(this).save(item, relay.download(item));
            } else {
                result = new MediaItem(
                    item.id(), item.kind(), item.fileName(), item.title(),
                    Long.toString(System.currentTimeMillis()), item.downloadUrl(), item.thumbnailUrl(),
                    "generated", -1
                );
            }
            ensureNotCancelled(store, id);
            store.complete(id, result);
            notifyState(id, "URage completed", "Created " + item.fileName() + ".", false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            store.update(id, "cancelled", "Cancelled by Android.");
        } catch (Exception error) {
            String message = error.getMessage() == null ? "Workflow failed." : error.getMessage();
            store.update(id, "failed", message);
            notifyState(id, "Workflow failed", message, false);
        } finally {
            active.remove(parameters.getJobId());
            jobFinished(parameters, retry);
        }
    }

    private void ensureNotCancelled(WorkflowJobStore store, int id) throws InterruptedException {
        WorkflowJobStore.Job current = store.get(id);
        if (Thread.currentThread().isInterrupted() || (current != null && "cancelled".equals(current.state()))) {
            throw new InterruptedException("Workflow cancelled.");
        }
    }

    private DashboardApi.WorkflowItem generate(WorkflowJobStore.Job job) throws Exception {
        JSONObject value = job.options();
        if ("matrix".equals(job.backend())) {
            SecureMatrixRelayStore.Config config = new SecureMatrixRelayStore(this).load();
            if (config == null) throw new IllegalStateException("Configure Matrix Internet Relay first.");
            MatrixSdkRelayClient relay = new MatrixSdkRelayClient(this, config);
            attachEncryptedMatrixSource(value, relay);
            return switch (job.kind()) {
                case "image" -> relay.generateImage(imageOptions(value));
                case "audio" -> relay.generateAudio(value.getString("prompt"), value.optInt("seconds", 10));
                case "music" -> relay.generateMusic(value.optString("tags"), value.optString("lyrics"), value.optInt("seconds", 30));
                case "video" -> relay.generateVideo(
                    value.getString("prompt"), value.optString("negativePrompt"),
                    value.optInt("seconds", 5), value.optInt("fps", 24),
                    value.optInt("width", 1024), value.optInt("height", 576),
                    value.has("steps") ? value.optInt("steps") : null,
                    value.has("seed") ? value.optLong("seed") : null,
                    value.optString("imageId"), value.optString("imageFileName"),
                    value.optString("matrixSourceId"), value.optString("matrixSourceFileName")
                );
                case "model3d" -> relay.generateModel3d(
                    value.optString("sourceMode"), value.optString("prompt"), value.optString("imageId"),
                    value.optString("imageFileName"), value.optString("matrixSourceId"),
                    value.optString("matrixSourceFileName"), value.optBoolean("generateLowPoly")
                );
                default -> throw new IllegalArgumentException("Unsupported workflow kind: " + job.kind());
            };
        }
        SecurePairingStore.Pairing pairing = new SecurePairingStore(this).load();
        if (pairing == null) throw new IllegalStateException("Pair with a dashboard first.");
        DashboardApi api = new DashboardApi(pairing.baseUrl(), pairing.token(), pairing.certificateSha256());
        return switch (job.kind()) {
            case "image" -> api.generateImage(imageOptions(value));
            case "audio" -> api.generateAudio(value.getString("prompt"), value.optInt("seconds", 10));
            case "music" -> api.generateMusic(value.optString("tags"), value.optString("lyrics"), value.optInt("seconds", 30));
            case "video" -> api.generateVideo(
                value.getString("prompt"), value.optString("negativePrompt"),
                value.optInt("seconds", 5), value.optInt("fps", 24),
                value.optInt("width", 1024), value.optInt("height", 576),
                value.has("steps") ? value.optInt("steps") : null,
                value.has("seed") ? value.optLong("seed") : null,
                value.optString("imageId"), value.optString("imageFileName")
            );
            case "model3d" -> api.generateModel3d(value.optString("sourceMode"), value.optString("prompt"), value.optString("imageId"),
                value.optString("imageFileName"), value.optBoolean("generateLowPoly"));
            default -> throw new IllegalArgumentException("Unsupported workflow kind: " + job.kind());
        };
    }

    private DashboardApi.ImageWorkflowOptions imageOptions(JSONObject value) {
        return new DashboardApi.ImageWorkflowOptions(
            value.optString("prompt"), value.optString("negativePrompt"),
            value.optInt("width", 1024), value.optInt("height", 1024),
            value.has("seed") ? value.optLong("seed") : null,
            value.has("steps") ? value.optInt("steps") : null,
            value.has("cfg") ? value.optDouble("cfg") : null,
            value.optBoolean("autoPrompt", true),
            value.optString("imageId"), value.optString("imageFileName"),
            value.optString("matrixSourceId"), value.optString("matrixSourceFileName")
        );
    }

    private void attachEncryptedMatrixSource(JSONObject value, MatrixSdkRelayClient relay) throws Exception {
        String uri = value.optString("sourceImageUri");
        if (uri.isBlank()) return;
        MatrixSdkRelayClient.SourceImageReference source = relay.uploadSourceImage(
            Uri.parse(uri), value.optString("sourceImageFileName", "matrix-source.jpg")
        );
        value.put("matrixSourceId", source.getId());
        value.put("matrixSourceFileName", source.getFileName());
        value.remove("sourceImageUri");
        value.remove("sourceImageFileName");
    }

    private void progress(WorkflowJobStore store, int id, String state, String detail) {
        store.update(id, state, detail);
        notifyState(id, "URage " + state, detail, !state.equals("completed"));
    }

    private void notifyState(int id, String title, String detail, boolean ongoing) {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Workflow jobs", NotificationManager.IMPORTANCE_DEFAULT));
        }
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new android.app.Notification.Builder(this, CHANNEL_ID)
            : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail).setOngoing(ongoing).setAutoCancel(!ongoing);
        if (ongoing) builder.setProgress(0, 0, true);
        notifications.notify(id, builder.build());
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Future<?> future = active.remove(parameters.getJobId());
        if (future != null) future.cancel(true);
        new WorkflowJobStore(this).update(parameters.getJobId(), "cancelled", "Cancelled before completion.");
        return false;
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
