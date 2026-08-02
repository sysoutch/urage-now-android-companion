package com.uragestudio.companion;

import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Owns Image Studio source selection, camera capture, and source-aware LLM actions. */
final class ImageStudioSourceController {
    private final MediaStudioSupport support;
    private final List<MediaItem> available = new ArrayList<>();
    private final List<MediaItem> selected = new ArrayList<>();

    ImageStudioSourceController(MediaStudioSupport support) {
        this.support = support;
    }

    void addTo(LinearLayout panel, EditText prompt, EditText negativePrompt) {
        Spinner source = support.choice(new ArrayList<>(List.of("No source image")), 0);
        panel.addView(support.ui.overline("Source images"), support.layout());
        panel.addView(source, support.layout());
        Button load = support.button("Load Recent Gallery Images");
        load.setOnClickListener(ignored -> support.loadImageChoices(available, source, load, "No source image"));
        panel.addView(load, support.layout());

        TextView summary = support.result("No source images selected.");
        panel.addView(summary, support.layout());
        Button add = support.button("Add Selected Source");
        add.setOnClickListener(ignored -> {
            int position = source.getSelectedItemPosition();
            if (position > 0 && position <= available.size()) add(available.get(position - 1), summary);
        });
        panel.addView(add, support.layout());

        LinearLayout cameras = new LinearLayout(support.activity);
        Button back = support.button("Capture Back Camera");
        back.setOnClickListener(ignored -> support.captureImage(false, item -> add(item, summary)));
        cameras.addView(back, support.ui.weighted());
        Button front = support.button("Capture Front Camera");
        front.setOnClickListener(ignored -> support.captureImage(true, item -> add(item, summary)));
        cameras.addView(front, support.ui.weighted());
        panel.addView(cameras, support.layout());

        Button clear = support.button("Clear Source Images");
        clear.setOnClickListener(ignored -> {
            selected.clear();
            render(summary);
        });
        panel.addView(clear, support.layout());

        LinearLayout interpret = new LinearLayout(support.activity);
        Button whole = support.button("Interpret Whole");
        whole.setOnClickListener(ignored ->
            support.interpretImages(selected, "whole", prompt.getText().toString(), prompt::setText));
        interpret.addView(whole, support.ui.weighted());
        Button parts = support.button("Interpret Parts");
        parts.setOnClickListener(ignored ->
            support.interpretImages(selected, "parts", prompt.getText().toString(), prompt::setText));
        interpret.addView(parts, support.ui.weighted());
        panel.addView(interpret, support.layout());

        EditText direction = support.input("Optional improvement direction");
        panel.addView(support.ui.field(
            "Prompt assistant", "Improve the current prompt or give LazyDev a specific direction.", direction
        ), support.layout());
        Button improve = support.button("Improve Prompt");
        improve.setOnClickListener(ignored -> support.improvePrompt(
            prompt.getText().toString(), negativePrompt.getText().toString(),
            direction.getText().toString(), prompt::setText
        ));
        panel.addView(improve, support.layout());
    }

    MediaItem primarySource() {
        return selected.isEmpty() ? null : selected.get(0);
    }

    private void add(MediaItem item, TextView summary) {
        boolean exists = selected.stream().anyMatch(source ->
            source.id().equals(item.id()) && source.fileName().equals(item.fileName()));
        if (!exists) selected.add(item);
        render(summary);
    }

    private void render(TextView summary) {
        if (selected.isEmpty()) {
            summary.setText("No source images selected.");
            return;
        }
        StringBuilder text = new StringBuilder("Selected source images:");
        for (MediaItem source : selected) {
            text.append("\n• ").append(source.title().isBlank() ? source.fileName() : source.title());
        }
        summary.setText(text.toString());
    }
}
