package com.uragestudio.companion;

import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class Model3dStudioController {
    private final MediaStudioSupport support;
    private final ScrollView view;

    Model3dStudioController(MediaStudioSupport support) {
        this.support = support;
        view = support.scroll(build());
    }

    View view() { return view; }
    void show(boolean visible) { view.setVisibility(visible ? View.VISIBLE : View.GONE); }

    private LinearLayout build() {
        LinearLayout panel = support.panel("3D Studio", "Turn a selected image into a 3D model, or create the source image from text first.");
        support.addStudioContext(panel);
        Spinner sourceMode = support.choice(List.of(
            "Use an existing image",
            "Generate source image from text"
        ), 0);
        panel.addView(support.ui.field(
            "Input workflow",
            "Image-to-3D is the default. The text option generates an image first and then sends that image directly into 3D generation.",
            sourceMode
        ), support.layout());

        EditText prompt = support.input("Describe the 3D object");
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP);
        View promptField = support.ui.field(
            "Source image prompt",
            "Describe one isolated object. This text is used to generate the required source image.",
            prompt
        );
        promptField.setVisibility(View.GONE);
        panel.addView(promptField, support.layout());

        List<MediaItem> sourceImages = new ArrayList<>();
        Spinner sourceImage = support.choice(new ArrayList<>(List.of("Select a source image")), 0);
        LinearLayout sourceGroup = new LinearLayout(support.activity);
        sourceGroup.setOrientation(LinearLayout.VERTICAL);
        sourceGroup.addView(support.ui.overline("Required source image"), support.layout());
        sourceGroup.addView(sourceImage, support.layout());
        Button refreshSources = support.button("Load Recent Gallery Images");
        refreshSources.setOnClickListener(ignored ->
            support.loadImageChoices(sourceImages, sourceImage, refreshSources, "Select a source image"));
        sourceGroup.addView(refreshSources, support.layout());
        LinearLayout cameraRow = new LinearLayout(support.activity);
        Button backCamera = support.button("Capture Back Camera");
        backCamera.setOnClickListener(ignored ->
            support.captureImage(false, item -> selectCapturedSource(sourceImages, sourceImage, item)));
        cameraRow.addView(backCamera, support.ui.weighted());
        Button frontCamera = support.button("Capture Front Camera");
        frontCamera.setOnClickListener(ignored ->
            support.captureImage(true, item -> selectCapturedSource(sourceImages, sourceImage, item)));
        cameraRow.addView(frontCamera, support.ui.weighted());
        sourceGroup.addView(cameraRow, support.layout());
        panel.addView(sourceGroup, support.layout());

        CheckBox lowPoly = support.checkBox("Also generate a low-poly version", false);
        panel.addView(lowPoly, support.layout());
        panel.addView(support.presets.create("model3d", () -> new JSONObject()
            .put("sourceMode", sourceMode.getSelectedItemPosition())
            .put("prompt", prompt.getText().toString()).put("lowPoly", lowPoly.isChecked()), values -> {
                sourceMode.setSelection(values.optInt("sourceMode", 0));
                prompt.setText(values.optString("prompt"));
                lowPoly.setChecked(values.optBoolean("lowPoly"));
            }), support.layout());
        StudioWorkflowResultView result = support.workflowResult("Generated models will appear here.");
        support.bindResult("model3d", result);
        support.bindBambuStudioAction(result);
        panel.addView(result, support.layout());
        Button generate = support.primaryButton("Generate 3D model");
        sourceMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                boolean generateSource = position == 1;
                promptField.setVisibility(generateSource ? View.VISIBLE : View.GONE);
                sourceGroup.setVisibility(generateSource ? View.GONE : View.VISIBLE);
                generate.setText(generateSource ? "Generate image + 3D model" : "Generate 3D model");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        generate.setOnClickListener(ignored ->
            queue(sourceMode, prompt, sourceImages, sourceImage, lowPoly, result));
        panel.addView(generate, support.layout());
        panel.addView(support.jobsButton(), support.layout());
        return panel;
    }

    private void queue(
        Spinner sourceMode, EditText prompt, List<MediaItem> sourceImages, Spinner sourceImage,
        CheckBox lowPoly, StudioWorkflowResultView result
    ) {
        String value = prompt.getText().toString().trim();
        boolean generateSource = sourceMode.getSelectedItemPosition() == 1;
        int position = sourceImage.getSelectedItemPosition();
        if (generateSource && value.isEmpty()) {
            support.status.accept("Describe the source image to generate first.");
            return;
        }
        if (!generateSource && (position <= 0 || position > sourceImages.size())) {
            support.status.accept("Select or capture a source image before generating the 3D model.");
            return;
        }
        try {
            JSONObject job = new JSONObject()
                .put("sourceMode", generateSource ? "generate-image" : "existing-image")
                .put("prompt", value)
                .put("generateLowPoly", lowPoly.isChecked());
            if (!generateSource) {
                MediaItem selected = sourceImages.get(position - 1);
                support.putImageSource(job, selected);
            }
            support.queue("model3d", job, result, "3D");
        } catch (Exception error) {
            support.status.accept(support.message(error, "Could not queue 3D generation."));
        }
    }

    @SuppressWarnings("unchecked")
    private void selectCapturedSource(List<MediaItem> sources, Spinner spinner, MediaItem item) {
        sources.add(item);
        StyledSpinnerAdapter<String> adapter = (StyledSpinnerAdapter<String>) spinner.getAdapter();
        adapter.add(item.title().isBlank() ? item.fileName() : item.title());
        adapter.notifyDataSetChanged();
        spinner.setSelection(adapter.getCount() - 1);
    }
}
