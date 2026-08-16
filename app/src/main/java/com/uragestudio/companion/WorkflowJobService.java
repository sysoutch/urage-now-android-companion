package com.uragestudio.companion;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
            notifyCompleted(id, result);
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
            case "audio" -> api.generateAudio(
                value.getString("prompt"), value.optInt("seconds", 10),
                value.has("steps") ? value.optInt("steps") : null,
                value.has("cfg") ? value.optDouble("cfg") : null
            );
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
        notifyState(id, title, detail, ongoing, null, null);
    }

    private void notifyCompleted(int id, MediaItem item) {
        notifyState(id, "URage completed", "Created " + item.fileName() + ".", false,
            loadNotificationPreview(item), item);
    }

    private void notifyState(int id, String title, String detail, boolean ongoing, Bitmap preview, MediaItem item) {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Workflow jobs", NotificationManager.IMPORTANCE_DEFAULT));
        }
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new android.app.Notification.Builder(this, CHANNEL_ID)
            : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail).setOngoing(ongoing).setAutoCancel(!ongoing);
        if (item != null) builder.setContentIntent(generationPendingIntent(id, item));
        if (preview != null) {
            builder.setLargeIcon(preview);
            if (Build.VERSION.SDK_INT >= 16) {
                builder.setStyle(new android.app.Notification.BigPictureStyle().bigPicture(preview).bigLargeIcon((Bitmap) null));
            }
        }
        if (ongoing) builder.setProgress(0, 0, true);
        notifications.notify(id, builder.build());
    }

    private PendingIntent generationPendingIntent(int id, MediaItem item) {
        Intent intent = new Intent(this, MainActivity.class)
            .setAction("com.uragestudio.companion.OPEN_GENERATION")
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("generationId", item.id())
            .putExtra("generationKind", item.kind())
            .putExtra("generationFileName", item.fileName())
            .putExtra("generationTitle", item.title())
            .putExtra("generationCreatedAt", item.createdAt())
            .putExtra("generationDownloadUrl", item.downloadUrl())
            .putExtra("generationThumbnailUrl", item.thumbnailUrl())
            .putExtra("generationSource", item.source())
            .putExtra("generationSize", item.size());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, id, intent, flags);
    }

    private Bitmap loadNotificationPreview(MediaItem item) {
        try {
            String thumbnailUrl = item.thumbnailUrl();
            if (!thumbnailUrl.isBlank() && !thumbnailUrl.startsWith("file:")) {
                SecurePairingStore.Pairing pairing = new SecurePairingStore(this).load();
                if (pairing != null) {
                    DashboardApi api = new DashboardApi(pairing.baseUrl(), pairing.token(), pairing.certificateSha256());
                    MediaItem thumbnail = new MediaItem(item.id(), item.kind(), item.fileName(), item.title(), item.createdAt(), thumbnailUrl, thumbnailUrl, item.source(), -1);
                    try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                        api.download(thumbnail, output);
                        byte[] bytes = output.toByteArray();
                        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (decoded != null) return scalePreview(decoded);
                    }
                }
            }
        } catch (Exception ignored) {
            // Completion notifications still work when a remote preview is unavailable.
        }
        return createKindPreview(item.kind());
    }

    private Bitmap scalePreview(Bitmap bitmap) {
        int width = Math.min(640, bitmap.getWidth());
        int height = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) bitmap.getWidth())));
        return Bitmap.createScaledBitmap(bitmap, width, height, true);
    }

    private Bitmap createKindPreview(String kind) {
        Bitmap bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int color = switch (kind == null ? "" : kind) {
            case "image" -> Color.rgb(54, 190, 128);
            case "model3d" -> Color.rgb(124, 112, 255);
            case "audio" -> Color.rgb(247, 164, 69);
            case "music" -> Color.rgb(232, 87, 174);
            case "video" -> Color.rgb(90, 143, 255);
            default -> Color.rgb(92, 106, 130);
        };
        canvas.drawColor(Color.rgb(16, 20, 31));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(64f);
        paint.setFakeBoldText(true);
        canvas.drawText(kind == null || kind.isBlank() ? "MEDIA" : kind.toUpperCase(), 256, 145, paint);
        return bitmap;
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
