package com.uragestudio.companion;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

final class WorkflowJobStore {
    interface Observation extends AutoCloseable {
        @Override void close();
    }
    record Job(
        int id, String kind, String backend, String state, String detail,
        JSONObject options, MediaItem result, long updatedAt
    ) {}
    private final SharedPreferences preferences;

    WorkflowJobStore(Context context) {
        preferences = context.getSharedPreferences("workflow_jobs", Context.MODE_PRIVATE);
    }

    synchronized void create(int id, String kind, String backend, JSONObject options) {
        save(new Job(id, kind, backend, "queued", "Waiting for Android to start the job.", options, null, System.currentTimeMillis()));
    }

    synchronized void update(int id, String state, String detail) {
        Job current = get(id);
        if (current != null) {
            save(new Job(id, current.kind, current.backend, state, detail, current.options, current.result, System.currentTimeMillis()));
        }
    }

    synchronized void complete(int id, MediaItem result) {
        Job current = get(id);
        if (current != null) {
            save(new Job(
                id, current.kind, current.backend, "completed", "Created " + result.fileName() + ".",
                current.options, result, System.currentTimeMillis()
            ));
        }
    }

    synchronized Job get(int id) {
        String value = preferences.getString("job." + id, "");
        if (value.isBlank()) return null;
        try {
            JSONObject item = new JSONObject(value);
            JSONObject result = item.optJSONObject("result");
            return new Job(id, item.getString("kind"), item.getString("backend"), item.getString("state"),
                item.optString("detail"), item.getJSONObject("options"),
                result == null ? null : readMediaItem(result), item.optLong("updatedAt"));
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized List<Job> list() {
        List<Job> jobs = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith("job.")) continue;
            try {
                Job job = get(Integer.parseInt(key.substring(4)));
                if (job != null) jobs.add(job);
            } catch (NumberFormatException ignored) {}
        }
        jobs.sort(Comparator.comparingLong(Job::updatedAt).reversed());
        return jobs;
    }

    synchronized Map<String, Integer> activeCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Job job : list()) {
            if (!isActive(job.state())) continue;
            counts.merge(job.kind(), 1, Integer::sum);
        }
        return counts;
    }

    Observation observe(Runnable listener) {
        SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (ignored, key) -> {
            if (key != null && key.startsWith("job.")) listener.run();
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        listener.run();
        return () -> preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
    }

    private boolean isActive(String state) {
        return "queued".equals(state) || "running".equals(state) || "downloading".equals(state);
    }

    private void save(Job job) {
        JSONObject value = new JSONObject();
        try {
            value.put("kind", job.kind).put("backend", job.backend).put("state", job.state)
                .put("detail", job.detail).put("options", job.options).put("updatedAt", job.updatedAt);
            if (job.result != null) value.put("result", writeMediaItem(job.result));
            preferences.edit().putString("job." + job.id, value.toString()).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist workflow job.", error);
        }
    }

    private JSONObject writeMediaItem(MediaItem item) throws Exception {
        return new JSONObject()
            .put("id", item.id()).put("kind", item.kind()).put("fileName", item.fileName())
            .put("title", item.title()).put("createdAt", item.createdAt())
            .put("downloadUrl", item.downloadUrl()).put("thumbnailUrl", item.thumbnailUrl())
            .put("source", item.source()).put("size", item.size());
    }

    private MediaItem readMediaItem(JSONObject item) {
        return new MediaItem(
            item.optString("id"), item.optString("kind"), item.optString("fileName"),
            item.optString("title"), item.optString("createdAt"), item.optString("downloadUrl"),
            item.optString("thumbnailUrl"), item.optString("source"), item.optLong("size", -1)
        );
    }
}
