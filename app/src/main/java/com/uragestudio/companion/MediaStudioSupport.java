package com.uragestudio.companion;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Shared presentation and workflow operations used by focused media Studio controllers. */
final class MediaStudioSupport {
    final Activity activity;
    final ExecutorService executor;
    final Handler main;
    final Supplier<DashboardApi> dashboardApi;
    final Supplier<MatrixSdkRelayClient> matrixRelay;
    final Consumer<String> status;
    final MobileUiKit ui;
    final PromptPresetUi presets;
    private final Supplier<String> route;
    private final StudioWorkflowResultPresenter results;
    private final StudioSourceImageActions sourceImages;

    MediaStudioSupport(
        Activity activity, ExecutorService executor, Handler main,
        Supplier<DashboardApi> dashboardApi, Supplier<MatrixSdkRelayClient> matrixRelay,
        Supplier<String> route, Consumer<String> status, Consumer<Exception> errors,
        Runnable refreshGallery, CameraCaptureController camera
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.dashboardApi = dashboardApi;
        this.matrixRelay = matrixRelay;
        this.route = route;
        this.status = status;
        ui = new MobileUiKit(activity);
        presets = new PromptPresetUi(activity, ui, status);
        sourceImages = new StudioSourceImageActions(
            activity, executor, main, dashboardApi, matrixRelay, this::usesMatrix, status, camera
        );
        results = new StudioWorkflowResultPresenter(
            activity, main,
            new MediaPreviewController(activity, executor, main, new MediaPreviewCache(activity, dashboardApi), errors),
            dashboardApi, executor, errors, refreshGallery
        );
    }

    void captureImage(boolean frontFacing, Consumer<MediaItem> onCaptured) {
        sourceImages.capture(frontFacing, onCaptured);
    }

    void interpretImages(List<MediaItem> images, String mode, String currentPrompt, Consumer<String> onPrompt) {
        sourceImages.interpret(images, mode, currentPrompt, onPrompt);
    }

    void improvePrompt(String prompt, String negativePrompt, String instructions, Consumer<String> onPrompt) {
        sourceImages.improvePrompt(prompt, negativePrompt, instructions, onPrompt);
    }

    LinearLayout panel(String title, String description) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(24));
        panel.addView(ui.screenTitle(title));
        TextView descriptionView = ui.body(description);
        descriptionView.setPadding(0, dp(3), 0, dp(10));
        panel.addView(descriptionView);
        return panel;
    }

    ScrollView scroll(View content) {
        ScrollView view = new ScrollView(activity);
        view.addView(content);
        view.setVisibility(View.GONE);
        return view;
    }

    void addStudioContext(LinearLayout panel) {
        panel.addView(ui.status("Uses the connection selected under Connect for this workflow."), layout());
    }

    EditText input(String hint) {
        EditText input = ui.input(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    Spinner choice(List<String> values, int selected) {
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new StyledSpinnerAdapter<>(activity, values));
        spinner.setPadding(dp(10), dp(4), dp(10), dp(4));
        spinner.setMinimumHeight(dp(52));
        spinner.setBackground(ui.controlBackground());
        spinner.setSelection(Math.max(0, Math.min(selected, values.size() - 1)));
        return spinner;
    }

    CheckBox checkBox(String label, boolean checked) {
        CheckBox box = new CheckBox(activity);
        box.setText(label);
        box.setTextColor(ui.textColor());
        box.setChecked(checked);
        box.setButtonTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{ui.accentStrongColor(), ui.textMutedColor()}
        ));
        return box;
    }

    TextView result(String value) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(13);
        result.setTextColor(ui.textMutedColor());
        result.setPadding(dp(12), dp(10), dp(12), dp(10));
        result.setBackground(ui.controlBackground());
        return result;
    }

    StudioWorkflowResultView workflowResult(String value) {
        return new StudioWorkflowResultView(activity, ui, value);
    }

    void bindResult(String kind, StudioWorkflowResultView result) {
        results.bind(kind, result);
    }

    void bindBambuStudioAction(StudioWorkflowResultView result) {
        result.setAction("Open in Bambu Studio", item -> {
            if (usesMatrix()) {
                status.accept("Bambu Studio opens on the paired dashboard host; connect through LAN or HTTPS to send this model.");
                return;
            }
            DashboardApi api = dashboardApi.get();
            if (api == null) {
                status.accept("Pair a dashboard before opening models in Bambu Studio.");
                return;
            }
            executor.execute(() -> {
                try {
                    api.openModelInBambuStudio(item);
                    main.post(() -> status.accept("Opened " + item.fileName() + " in Bambu Studio on the dashboard host."));
                } catch (Exception error) {
                    main.post(() -> status.accept(message(error, "Could not open the model in Bambu Studio.")));
                }
            });
        });
    }

    void close() {
        results.close();
    }

    Button button(String label) {
        return ui.button(label, MobileUiKit.ActionStyle.SECONDARY);
    }

    Button primaryButton(String label) {
        return ui.button(label, MobileUiKit.ActionStyle.PRIMARY);
    }

    Button jobsButton() {
        Button button = button("Background Jobs");
        button.setOnClickListener(ignored -> {
            List<WorkflowJobStore.Job> jobs = new WorkflowJobStore(activity).list();
            if (jobs.isEmpty()) {
                status.accept("No background workflow jobs yet.");
                return;
            }
            String[] labels = jobs.stream()
                .map(job -> "#" + job.id() + " · " + job.kind() + " · " + job.state() + "\n" + job.detail())
                .toArray(String[]::new);
            new android.app.AlertDialog.Builder(activity)
                .setTitle("Background workflow jobs")
                .setItems(labels, (dialog, index) -> {
                    WorkflowJobStore.Job selected = jobs.get(index);
                    if ("queued".equals(selected.state()) || "running".equals(selected.state()) || "downloading".equals(selected.state())) {
                        WorkflowJobScheduler.cancel(activity, selected.id());
                        status.accept("Cancelled workflow job #" + selected.id() + ".");
                    }
                })
                .setNegativeButton("Close", null).show();
        });
        return button;
    }

    void loadImageChoices(List<MediaItem> images, Spinner spinner, Button trigger, String emptyLabel) {
        sourceImages.loadChoices(images, spinner, trigger, emptyLabel);
    }

    void queue(String kind, JSONObject options, StudioWorkflowResultView result, String label) {
        boolean matrix = usesMatrix();
        if (matrix && matrixRelay.get() == null) {
            status.accept("Configure the Matrix Internet connection under Connect first.");
            return;
        }
        if (!matrix && dashboardApi.get() == null) return;
        int jobId = WorkflowJobScheduler.enqueue(activity, kind, matrix ? "matrix" : "dashboard", options);
        result.showStatus("Background job #" + jobId + " queued through " + (matrix ? "Matrix" : "LAN") + ".");
        status.accept(label + " generation queued.");
    }

    boolean usesMatrix() {
        return ConnectionRouteStore.MATRIX.equals(route.get());
    }

    Long optionalLong(EditText input) {
        try {
            String value = input.getText().toString().trim();
            return value.isEmpty() ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    void putImageSource(JSONObject options, MediaItem source) throws org.json.JSONException {
        sourceImages.put(options, source);
    }

    Integer optionalInteger(EditText input) {
        Long value = optionalLong(input);
        return value == null ? null : value.intValue();
    }

    Double optionalDouble(EditText input) {
        try {
            String value = input.getText().toString().trim();
            return value.isEmpty() ? null : Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    LinearLayout.LayoutParams layout() {
        return ui.spacedMatchWrap();
    }

    int dp(int value) {
        return ui.dp(value);
    }

    String message(Exception error, String fallback) {
        return error.getMessage() == null ? fallback : error.getMessage();
    }
}
