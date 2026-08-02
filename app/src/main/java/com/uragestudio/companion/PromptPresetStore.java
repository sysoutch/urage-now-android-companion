package com.uragestudio.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Persists reusable, Studio-scoped prompt configurations. */
final class PromptPresetStore {
    record Preset(String id, String name, boolean favorite, JSONObject values) {}

    private final SharedPreferences preferences;

    PromptPresetStore(Context context) {
        preferences = context.getSharedPreferences("prompt_presets", Context.MODE_PRIVATE);
    }

    synchronized List<Preset> list(String studio) {
        List<Preset> presets = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(key(studio), "[]"));
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.optJSONObject(index);
                if (item == null) continue;
                String id = item.optString("id");
                String name = item.optString("name").trim();
                JSONObject fields = item.optJSONObject("values");
                if (!id.isBlank() && !name.isBlank() && fields != null) {
                    presets.add(new Preset(id, name, item.optBoolean("favorite"), fields));
                }
            }
        } catch (Exception ignored) {
            // A malformed local preset collection should not block the Studio.
        }
        presets.sort(Comparator.comparing(Preset::favorite).reversed()
            .thenComparing(Preset::name, String.CASE_INSENSITIVE_ORDER));
        return presets;
    }

    synchronized Preset save(String studio, String name, JSONObject values) {
        List<Preset> presets = list(studio);
        Preset preset = new Preset(UUID.randomUUID().toString(), name.trim(), false, copy(values));
        presets.add(preset);
        persist(studio, presets);
        return preset;
    }

    synchronized void toggleFavorite(String studio, String id) {
        List<Preset> updated = list(studio).stream()
            .map(item -> item.id().equals(id)
                ? new Preset(item.id(), item.name(), !item.favorite(), item.values())
                : item)
            .toList();
        persist(studio, updated);
    }

    synchronized void delete(String studio, String id) {
        persist(studio, list(studio).stream().filter(item -> !item.id().equals(id)).toList());
    }

    private void persist(String studio, List<Preset> presets) {
        JSONArray values = new JSONArray();
        for (Preset preset : presets) {
            try {
                values.put(new JSONObject()
                    .put("id", preset.id())
                    .put("name", preset.name())
                    .put("favorite", preset.favorite())
                    .put("values", preset.values()));
            } catch (Exception ignored) {
                // Preserve the valid presets when one local entry cannot be serialized.
            }
        }
        preferences.edit().putString(key(studio), values.toString()).apply();
    }

    private JSONObject copy(JSONObject values) {
        try {
            return new JSONObject(values == null ? "{}" : values.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String key(String studio) {
        return "presets." + studio.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
