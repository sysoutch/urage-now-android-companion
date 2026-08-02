package com.uragestudio.companion;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class MatrixMediaGalleryStore {
    private final Context context;
    private final File directory;
    private final File indexFile;

    MatrixMediaGalleryStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "matrix-gallery");
        indexFile = new File(directory, "index.json");
    }

    synchronized MediaItem save(DashboardApi.WorkflowItem item, MatrixSdkRelayClient.MediaDownload media) throws Exception {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create the Matrix gallery.");
        }
        String safeName = safeFileName(media.getFileName());
        File destination = uniqueFile(safeName);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(media.getBytes());
        }
        JSONObject entry = new JSONObject()
            .put("id", item.id().isBlank() ? destination.getName() : item.id())
            .put("kind", item.kind())
            .put("fileName", destination.getName())
            .put("title", item.title())
            .put("createdAt", System.currentTimeMillis())
            .put("contentType", media.getContentType())
            .put("size", destination.length());
        JSONArray index = readIndex();
        index.put(entry);
        writeIndex(index);
        return toMediaItem(entry);
    }

    synchronized List<MediaItem> list(String kind) {
        List<MediaItem> result = new ArrayList<>();
        JSONArray index = readIndex();
        for (int i = 0; i < index.length(); i++) {
            JSONObject entry = index.optJSONObject(i);
            if (entry != null && kind.equals(entry.optString("kind"))) {
                File file = new File(directory, entry.optString("fileName"));
                if (file.isFile()) result.add(toMediaItem(entry));
            }
        }
        result.sort(Comparator.comparing(MediaItem::createdAt).reversed());
        return result;
    }

    private MediaItem toMediaItem(JSONObject entry) {
        File file = new File(directory, entry.optString("fileName"));
        String localUrl = file.toURI().toString();
        boolean image = "image".equals(entry.optString("kind"));
        return new MediaItem(
            entry.optString("id"), entry.optString("kind"), entry.optString("fileName"),
            entry.optString("title"), Long.toString(entry.optLong("createdAt")),
            localUrl, image ? localUrl : "", "matrix", entry.optLong("size", file.length())
        );
    }

    private JSONArray readIndex() {
        try {
            if (!indexFile.isFile()) return new JSONArray();
            try (FileInputStream input = new FileInputStream(indexFile); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                return new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void writeIndex(JSONArray index) throws Exception {
        try (FileOutputStream output = new FileOutputStream(indexFile)) {
            output.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private File uniqueFile(String fileName) {
        File candidate = new File(directory, fileName);
        if (!candidate.exists()) return candidate;
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String extension = dot < 0 ? "" : fileName.substring(dot);
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private String safeFileName(String value) {
        String safe = String.valueOf(value).replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "matrix-media-" + System.currentTimeMillis() + ".bin" : safe;
    }
}
