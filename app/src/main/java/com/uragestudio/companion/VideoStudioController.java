package com.uragestudio.companion;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class VideoStudioController {
    private final MediaStudioSupport support;
    private final ScrollView view;

    VideoStudioController(MediaStudioSupport support) {
        this.support = support;
        view = support.scroll(build());
    }

    View view() { return view; }
    void show(boolean visible) { view.setVisibility(visible ? View.VISIBLE : View.GONE); }

    private LinearLayout build() {
        LinearLayout panel = support.panel("Video Studio", "Generate a video clip with deliberate timing and framing controls.");
        support.addStudioContext(panel);
        EditText prompt = support.input("Describe the shot, motion, camera, lighting, and style");
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP);
        panel.addView(support.ui.field("Video brief", "Describe one coherent shot and how it should move over time.", prompt), support.layout());
        EditText negativePrompt = support.input("Artifacts or details to avoid (optional)");
        panel.addView(support.ui.field("Avoid", "Optional negative prompt.", negativePrompt), support.layout());
        List<MediaItem> sourceImages = new ArrayList<>();
        Spinner sourceImage = support.choice(new ArrayList<>(List.of("Text only")), 0);
        panel.addView(support.ui.overline("Source image"), support.layout());
        panel.addView(sourceImage, support.layout());
        Button refreshSources = support.button("Load Recent Gallery Images");
        refreshSources.setOnClickListener(ignored ->
            support.loadImageChoices(sourceImages, sourceImage, refreshSources, "Text only"));
        panel.addView(refreshSources, support.layout());
        Spinner size = support.choice(List.of("1024 × 576 · Landscape", "576 × 1024 · Portrait", "768 × 768 · Square"), 0);
        panel.addView(support.ui.overline("Frame size"), support.layout());
        panel.addView(size, support.layout());
        Spinner duration = support.choice(List.of("3 seconds", "5 seconds", "8 seconds", "10 seconds"), 1);
        panel.addView(support.ui.overline("Duration"), support.layout());
        panel.addView(duration, support.layout());
        Spinner fps = support.choice(List.of("24 fps", "30 fps"), 0);
        panel.addView(support.ui.overline("Frame rate"), support.layout());
        panel.addView(fps, support.layout());
        EditText steps = support.input("Steps (optional, 1–250)");
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(support.ui.field("Sampling steps", "Leave empty to use the dashboard workflow default.", steps), support.layout());
        EditText seed = support.input("Seed (empty = random)");
        seed.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(support.ui.field("Seed", "Reuse a seed when iterating on the same shot.", seed), support.layout());
        panel.addView(support.presets.create("video", () -> new JSONObject()
            .put("prompt", prompt.getText().toString()).put("negativePrompt", negativePrompt.getText().toString())
            .put("size", size.getSelectedItemPosition()).put("duration", duration.getSelectedItemPosition())
            .put("fps", fps.getSelectedItemPosition()).put("steps", steps.getText().toString())
            .put("seed", seed.getText().toString()), values -> {
                prompt.setText(values.optString("prompt"));
                negativePrompt.setText(values.optString("negativePrompt"));
                size.setSelection(Math.max(0, Math.min(values.optInt("size"), 2)));
                duration.setSelection(Math.max(0, Math.min(values.optInt("duration", 1), 3)));
                fps.setSelection(Math.max(0, Math.min(values.optInt("fps"), 1)));
                steps.setText(values.optString("steps"));
                seed.setText(values.optString("seed"));
            }), support.layout());
        StudioWorkflowResultView result = support.workflowResult("Generated videos will appear here.");
        support.bindResult("video", result);
        panel.addView(result, support.layout());
        Button generate = support.primaryButton("Generate video");
        generate.setOnClickListener(ignored ->
            queue(prompt, negativePrompt, sourceImages, sourceImage, size, duration, fps, steps, seed, result));
        panel.addView(generate, support.layout());
        panel.addView(support.jobsButton(), support.layout());
        return panel;
    }

    private void queue(
        EditText prompt, EditText negativePrompt, List<MediaItem> sourceImages, Spinner sourceImage,
        Spinner size, Spinner duration, Spinner fps, EditText steps, EditText seed, StudioWorkflowResultView result
    ) {
        String value = prompt.getText().toString().trim();
        if (value.isEmpty()) {
            support.status.accept("Enter a Video Studio prompt first.");
            return;
        }
        int[][] sizes = {{1024, 576}, {576, 1024}, {768, 768}};
        int[] selectedSize = sizes[size.getSelectedItemPosition()];
        int[] durations = {3, 5, 8, 10};
        int[] frameRates = {24, 30};
        try {
            JSONObject options = new JSONObject().put("prompt", value)
                .put("negativePrompt", negativePrompt.getText().toString().trim())
                .put("width", selectedSize[0]).put("height", selectedSize[1])
                .put("seconds", durations[duration.getSelectedItemPosition()])
                .put("fps", frameRates[fps.getSelectedItemPosition()]);
            Integer stepValue = support.optionalInteger(steps);
            Long seedValue = support.optionalLong(seed);
            if (stepValue != null) options.put("steps", stepValue);
            if (seedValue != null) options.put("seed", seedValue);
            int position = sourceImage.getSelectedItemPosition();
            if (position > 0 && position <= sourceImages.size()) {
                MediaItem selected = sourceImages.get(position - 1);
                support.putImageSource(options, selected);
            }
            support.queue("video", options, result, "Video");
        } catch (Exception error) {
            support.status.accept(support.message(error, "Could not queue video generation."));
        }
    }
}
