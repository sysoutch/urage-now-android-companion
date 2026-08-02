package com.uragestudio.companion;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import org.json.JSONObject;

final class ImageStudioController {
    private final MediaStudioSupport support;
    private final ScrollView view;

    ImageStudioController(MediaStudioSupport support) {
        this.support = support;
        view = support.scroll(build());
    }

    View view() { return view; }
    void show(boolean visible) { view.setVisibility(visible ? View.VISIBLE : View.GONE); }

    private LinearLayout build() {
        LinearLayout panel = support.panel("Image Studio", "Generate an image on the dashboard and make it available in the Gallery.");
        support.addStudioContext(panel);
        EditText prompt = support.input("Describe the image to generate");
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP);
        panel.addView(support.ui.field("Prompt", "Describe the subject, composition, lighting, and style.", prompt), support.layout());
        EditText negativePrompt = support.input("Negative prompt (optional)");
        panel.addView(support.ui.field("Avoid", "Optional details the generated image should exclude.", negativePrompt), support.layout());
        ImageStudioSourceController sources = new ImageStudioSourceController(support);
        sources.addTo(panel, prompt, negativePrompt);
        Spinner size = support.choice(List.of("1024 × 1024", "1344 × 768", "768 × 1344", "512 × 512"), 0);
        panel.addView(support.ui.overline("Canvas size"), support.layout());
        panel.addView(size, support.layout());
        EditText seed = support.input("Seed (empty = random)");
        seed.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(support.ui.field("Seed", "Leave empty for a new random composition.", seed), support.layout());
        EditText steps = support.input("Steps (optional, 1–250)");
        steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(support.ui.field("Sampling steps", "Optional, between 1 and 250.", steps), support.layout());
        EditText cfg = support.input("CFG (optional, 0–30)");
        cfg.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        panel.addView(support.ui.field("Prompt guidance", "Optional CFG value between 0 and 30.", cfg), support.layout());
        CheckBox autoPrompt = support.checkBox("Let LazyDev improve the prompt", true);
        panel.addView(autoPrompt, support.layout());
        panel.addView(support.presets.create("image", () -> new JSONObject()
            .put("prompt", prompt.getText().toString()).put("negativePrompt", negativePrompt.getText().toString())
            .put("size", size.getSelectedItemPosition()).put("seed", seed.getText().toString())
            .put("steps", steps.getText().toString()).put("cfg", cfg.getText().toString())
            .put("autoPrompt", autoPrompt.isChecked()), values -> {
                prompt.setText(values.optString("prompt"));
                negativePrompt.setText(values.optString("negativePrompt"));
                size.setSelection(Math.max(0, Math.min(values.optInt("size"), 3)));
                seed.setText(values.optString("seed"));
                steps.setText(values.optString("steps"));
                cfg.setText(values.optString("cfg"));
                autoPrompt.setChecked(values.optBoolean("autoPrompt", true));
            }), support.layout());
        StudioWorkflowResultView result = support.workflowResult("Generated images will appear here.");
        support.bindResult("image", result);
        panel.addView(result, support.layout());
        Button generate = support.primaryButton("Generate image");
        generate.setOnClickListener(ignored ->
            queue(prompt, negativePrompt, size, seed, steps, cfg, autoPrompt, sources.primarySource(), result));
        panel.addView(generate, support.layout());
        panel.addView(support.jobsButton(), support.layout());
        return panel;
    }

    private void queue(
        EditText prompt, EditText negativePrompt, Spinner size, EditText seed,
        EditText steps, EditText cfg, CheckBox autoPrompt, MediaItem source, StudioWorkflowResultView result
    ) {
        String value = prompt.getText().toString().trim();
        if (value.isEmpty()) {
            support.status.accept("Enter an Image Studio prompt first.");
            return;
        }
        int[][] sizes = {{1024, 1024}, {1344, 768}, {768, 1344}, {512, 512}};
        int[] selected = sizes[Math.max(0, Math.min(size.getSelectedItemPosition(), sizes.length - 1))];
        try {
            JSONObject job = new JSONObject()
                .put("prompt", value).put("negativePrompt", negativePrompt.getText().toString().trim())
                .put("width", selected[0]).put("height", selected[1]).put("autoPrompt", autoPrompt.isChecked());
            Long seedValue = support.optionalLong(seed);
            Integer stepValue = support.optionalInteger(steps);
            Double cfgValue = support.optionalDouble(cfg);
            if (seedValue != null) job.put("seed", seedValue);
            if (stepValue != null) job.put("steps", stepValue);
            if (cfgValue != null) job.put("cfg", cfgValue);
            support.putImageSource(job, source);
            support.queue("image", job, result, "Image");
        } catch (Exception error) {
            support.status.accept(support.message(error, "Could not queue image generation."));
        }
    }

}
