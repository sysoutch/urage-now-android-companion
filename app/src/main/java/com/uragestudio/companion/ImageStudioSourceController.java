package com.uragestudio.companion;

import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns Image Studio source selection, camera capture, and source-aware LLM actions. */
final class ImageStudioSourceController {
    private static final String SOURCE_SELECTION = "imageStudioSourceSelection";
    private final MediaStudioSupport support;
    private final SharedPreferences preferences;
    private final List<MediaItem> available = new ArrayList<>();
    private final List<MediaItem> selected = new ArrayList<>();

    ImageStudioSourceController(MediaStudioSupport support) {
        this.support = support;
        preferences = support.activity.getSharedPreferences("workflow_workspace", Context.MODE_PRIVATE);
        restoreSelection();
    }

    void addTo(
        LinearLayout panel,
        EditText prompt,
        EditText negativePrompt,
        EditText interpretationDirection
    ) {
        // Source images foldout.
        Button sourceHeader = support.button("Source images  ▸");
        sourceHeader.setGravity(
            android.view.Gravity.START
                | android.view.Gravity.CENTER_VERTICAL
        );
        sourceHeader.setContentDescription("Expand source images");

        LinearLayout sourceContent = new LinearLayout(support.activity);
        sourceContent.setOrientation(LinearLayout.VERTICAL);
        sourceContent.setVisibility(android.view.View.GONE);

        Spinner source = support.choice(
            new ArrayList<>(List.of("No source image")),
            0
        );
        sourceContent.addView(source, support.layout());

        Button load = support.button("Load Recent Gallery Images");
        load.setOnClickListener(ignored ->
            support.loadImageChoices(
                available,
                source,
                load,
                "No source image"
            )
        );
        sourceContent.addView(load, support.layout());

        TextView summary = support.result("No source images selected.");
        sourceContent.addView(summary, support.layout());
        render(summary);

        Button add = support.button("Add Selected Source");
        add.setOnClickListener(ignored -> {
            int position = source.getSelectedItemPosition();

            if (position > 0 && position <= available.size()) {
                add(available.get(position - 1), summary);
            }
        });
        sourceContent.addView(add, support.layout());

        LinearLayout cameras = new LinearLayout(support.activity);

        Button back = support.button("Capture Back Camera");
        back.setOnClickListener(ignored ->
            support.captureImage(
                false,
                item -> add(item, summary)
            )
        );
        cameras.addView(back, support.ui.weighted());

        Button front = support.button("Capture Front Camera");
        front.setOnClickListener(ignored ->
            support.captureImage(
                true,
                item -> add(item, summary)
            )
        );
        cameras.addView(front, support.ui.weighted());

        sourceContent.addView(cameras, support.layout());

        Button clear = support.button("Clear Source Images");
        clear.setOnClickListener(ignored -> {
            selected.clear();
            persistSelection();
            render(summary);
        });
        sourceContent.addView(clear, support.layout());

        LinearLayout interpret = new LinearLayout(support.activity);

        Button whole = support.button("Interpret Whole");
        whole.setOnClickListener(ignored ->
            support.interpretImages(
                selected,
                "whole",
                interpretationDirection.getText().toString(),
                text -> applyInterpretedPrompt(prompt, text)
            )
        );
        interpret.addView(whole, support.ui.weighted());

        Button parts = support.button("Interpret Parts");
        parts.setOnClickListener(ignored ->
            support.interpretImages(
                selected,
                "parts",
                interpretationDirection.getText().toString(),
                text -> applyInterpretedPrompt(prompt, text)
            )
        );
        interpret.addView(parts, support.ui.weighted());

        // Interpret Parts is the last control in the foldout.
        sourceContent.addView(interpret, support.layout());

        sourceHeader.setOnClickListener(view -> {
            boolean expanded =
                sourceContent.getVisibility() == android.view.View.VISIBLE;

            sourceContent.setVisibility(
                expanded
                    ? android.view.View.GONE
                    : android.view.View.VISIBLE
            );

            sourceHeader.setText(
                expanded
                    ? "Source images  ▸"
                    : "Source images  ▾"
            );

            sourceHeader.setContentDescription(
                expanded
                    ? "Expand source images"
                    : "Collapse source images"
            );
        });

        panel.addView(sourceHeader, support.layout());
        panel.addView(sourceContent, support.layout());

        // Prompt assistant remains outside the Source Images foldout.
        EditText direction =
            support.input("Optional improvement direction");

        panel.addView(
            support.ui.field(
                "Prompt assistant",
                "Improve the current prompt or give LazyDev a specific direction.",
                direction
            ),
            support.layout()
        );

        Button improve = support.button("Improve Prompt");
        improve.setOnClickListener(ignored ->
            support.improvePrompt(
                prompt.getText().toString(),
                negativePrompt.getText().toString(),
                direction.getText().toString(),
                prompt::setText
            )
        );
        panel.addView(improve, support.layout());
    }

    /** Applies the correlated bot prompt event to the visible Image Studio composer. */
    private void applyInterpretedPrompt(
        EditText prompt,
        String interpretedPrompt
    ) {
        String text =
            interpretedPrompt == null
                ? ""
                : interpretedPrompt.trim();

        if (text.isEmpty()) {
            support.status.accept(
                "Image interpretation returned an empty prompt."
            );
            return;
        }

        prompt.setText(text);
        prompt.setSelection(text.length());
        prompt.requestFocus();
    }

    private void add(MediaItem item, TextView summary) {
        boolean exists = selected.stream().anyMatch(source ->
            source.id().equals(item.id())
                && source.fileName().equals(item.fileName())
        );

        if (!exists) {
            selected.add(item);
            persistSelection();
        }

        render(summary);
    }

    private void render(TextView summary) {
        if (selected.isEmpty()) {
            summary.setText("No source images selected.");
            return;
        }

        StringBuilder text =
            new StringBuilder("Selected source images:");

        for (MediaItem source : selected) {
            text.append("\n• ")
                .append(
                    source.title().isBlank()
                        ? source.fileName()
                        : source.title()
                );
        }

        summary.setText(text.toString());
    }

    private void restoreSelection() {
        try {
            JSONArray stored = new JSONArray(preferences.getString(SOURCE_SELECTION, "[]"));
            for (int index = 0; index < stored.length(); index++) {
                JSONObject item = stored.optJSONObject(index);
                if (item == null) continue;
                selected.add(new MediaItem(item.optString("id"), item.optString("kind", "image"),
                    item.optString("fileName"), item.optString("title"), item.optString("createdAt"),
                    item.optString("downloadUrl"), item.optString("thumbnailUrl"), item.optString("source"), item.optLong("size")));
            }
        } catch (Exception ignored) {
            preferences.edit().remove(SOURCE_SELECTION).apply();
        }
    }

    private void persistSelection() {
        JSONArray stored = new JSONArray();
        for (MediaItem item : selected) {
            try {
                stored.put(new JSONObject().put("id", item.id()).put("kind", item.kind()).put("fileName", item.fileName())
                    .put("title", item.title()).put("createdAt", item.createdAt()).put("downloadUrl", item.downloadUrl())
                    .put("thumbnailUrl", item.thumbnailUrl()).put("source", item.source()).put("size", item.size()));
            } catch (Exception ignored) {
                // Ignore a malformed transient item instead of losing the rest of the source selection.
            }
        }
        preferences.edit().putString(SOURCE_SELECTION, stored.toString()).apply();
    }
}
