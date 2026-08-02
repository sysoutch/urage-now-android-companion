package com.uragestudio.companion;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns the opt-in durable copies used for offline Gallery browsing. */
final class OfflineMediaStore {
    private static final String KEY_ENABLED = "offlineMediaCacheEnabled";
    private final SharedPreferences preferences;
    private final File directory;
    private final File indexFile;

    OfflineMediaStore(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences("gallery_settings", Context.MODE_PRIVATE);
        directory = new File(app.getFilesDir(), "offline-media");
        indexFile = new File(directory, "index.json");
    }

    boolean enabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    File fileFor(MediaItem item) {
        return new File(directory, cacheName(item));
    }

    synchronized void remember(MediaItem item, File file) throws Exception {
        if (!enabled() || !file.isFile()) return;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create the offline media directory.");
        }
        JSONArray source = readIndex();
        JSONArray next = new JSONArray();
        String key = key(item);
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry != null && !key.equals(entry.optString("key"))) next.put(entry);
        }
        next.put(new JSONObject()
            .put("key", key).put("id", item.id()).put("kind", item.kind())
            .put("fileName", item.fileName()).put("title", item.title())
            .put("createdAt", item.createdAt()).put("cachedFile", file.getName())
            .put("size", file.length()));
        writeIndex(next);
    }

    synchronized List<MediaItem> list(String kind) {
        if (!enabled()) return List.of();
        List<MediaItem> items = new ArrayList<>();
        JSONArray index = readIndex();
        for (int position = 0; position < index.length(); position++) {
            JSONObject entry = index.optJSONObject(position);
            if (entry == null || !kind.equals(entry.optString("kind"))) continue;
            File file = new File(directory, entry.optString("cachedFile"));
            if (!file.isFile()) continue;
            String local = file.toURI().toString();
            items.add(new MediaItem(
                entry.optString("id"), kind, entry.optString("fileName"),
                entry.optString("title"), entry.optString("createdAt"),
                local, "image".equals(kind) ? local : "", "offline", file.length()
            ));
        }
        items.sort(Comparator.comparing(MediaItem::createdAt).reversed());
        return items;
    }

    synchronized void clear() {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) file.delete();
            }
        }
    }

    private String key(MediaItem item) {
        return item.kind() + ":" + item.id() + ":" + item.fileName();
    }

    private String cacheName(MediaItem item) {
        return (item.kind() + "-" + item.id() + "-" + item.fileName()).replaceAll("[^a-zA-Z0-9._-]", "_");
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
}
