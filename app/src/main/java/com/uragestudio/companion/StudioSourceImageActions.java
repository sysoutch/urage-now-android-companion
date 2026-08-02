package com.uragestudio.companion;

import android.app.Activity;
import android.widget.Button;
import android.widget.Spinner;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns camera/gallery source acquisition and source-aware prompt actions for mobile Studios. */
final class StudioSourceImageActions {
    private final Activity activity;
    private final ExecutorService executor;
    private final android.os.Handler main;
    private final Supplier<DashboardApi> dashboardApi;
    private final Supplier<MatrixSdkRelayClient> matrixRelay;
    private final Supplier<Boolean> usesMatrix;
    private final Consumer<String> status;
    private final CameraCaptureController camera;

    StudioSourceImageActions(
        Activity activity, ExecutorService executor, android.os.Handler main,
        Supplier<DashboardApi> dashboardApi, Supplier<MatrixSdkRelayClient> matrixRelay,
        Supplier<Boolean> usesMatrix, Consumer<String> status, CameraCaptureController camera
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.dashboardApi = dashboardApi;
        this.matrixRelay = matrixRelay;
        this.usesMatrix = usesMatrix;
        this.status = status;
        this.camera = camera;
    }

    void capture(boolean frontFacing, Consumer<MediaItem> onCaptured) {
        if (usesMatrix.get()) {
            if (matrixRelay.get() == null) {
                status.accept("Configure the Matrix Internet connection before capturing a source image.");
                return;
            }
            captureLocal(frontFacing, onCaptured);
            return;
        }
        DashboardApi api = dashboardApi.get();
        if (api == null) {
            status.accept("Pair with the dashboard before importing a camera image.");
            return;
        }
        try {
            camera.capture(frontFacing, uri -> {
                status.accept("Importing captured image…");
                executor.execute(() -> {
                    try {
                        String fileName = AndroidMediaStore.queryDisplayName(activity.getContentResolver(), uri);
                        MediaItem item = api.upload(activity.getContentResolver(), uri, "image", fileName, "image/jpeg");
                        main.post(() -> {
                            onCaptured.accept(item);
                            status.accept("Captured image imported.");
                        });
                    } catch (Exception error) {
                        main.post(() -> status.accept(message(error, "Could not import the captured image.")));
                    }
                });
            });
        } catch (Exception error) {
            status.accept(message(error, "Could not open the camera."));
        }
    }

    void interpret(List<MediaItem> images, String mode, String currentPrompt, Consumer<String> onPrompt) {
        if (images.stream().anyMatch(image -> "local".equals(image.source()) || "matrix".equals(image.source()))) {
            status.accept("Matrix-native vision interpretation is not available yet. Generate with the source image or switch to LAN.");
            return;
        }
        DashboardApi api = dashboardApi.get();
        if (api == null) {
            status.accept("Image interpretation requires a paired dashboard.");
            return;
        }
        if (images.isEmpty()) {
            status.accept("Add at least one source image first.");
            return;
        }
        status.accept("Interpreting " + images.size() + " source image(s)…");
        executor.execute(() -> {
            try {
                String interpreted = api.interpretImages(images, mode, currentPrompt);
                main.post(() -> {
                    onPrompt.accept(interpreted);
                    status.accept("Image interpretation added to the prompt.");
                });
            } catch (Exception error) {
                main.post(() -> status.accept(message(error, "Image interpretation failed.")));
            }
        });
    }

    void improvePrompt(String prompt, String negativePrompt, String instructions, Consumer<String> onPrompt) {
        DashboardApi api = dashboardApi.get();
        if (api == null) {
            status.accept("Prompt improvement requires a paired dashboard.");
            return;
        }
        if (prompt.isBlank()) {
            status.accept("Enter a prompt to improve first.");
            return;
        }
        status.accept("Improving the Image Studio prompt…");
        executor.execute(() -> {
            try {
                String improved = api.improveImagePrompt(prompt, negativePrompt, instructions);
                main.post(() -> {
                    onPrompt.accept(improved);
                    status.accept("Prompt improved.");
                });
            } catch (Exception error) {
                main.post(() -> status.accept(message(error, "Prompt improvement failed.")));
            }
        });
    }

    @SuppressWarnings("unchecked")
    void loadChoices(List<MediaItem> images, Spinner spinner, Button trigger, String emptyLabel) {
        if (usesMatrix.get()) {
            images.clear();
            images.addAll(new MatrixMediaGalleryStore(activity).list("image"));
            replaceChoices(images, spinner, emptyLabel);
            status.accept("Loaded " + images.size() + " local Matrix source image(s).");
            return;
        }
        DashboardApi api = dashboardApi.get();
        if (api == null) return;
        trigger.setEnabled(false);
        executor.execute(() -> {
            try {
                DashboardApi.MediaPage page = api.listMedia("image", null, 18);
                main.post(() -> {
                    images.clear();
                    images.addAll(page.items());
                    replaceChoices(images, spinner, emptyLabel);
                    trigger.setEnabled(true);
                    status.accept("Loaded " + images.size() + " recent source image(s).");
                });
            } catch (Exception error) {
                main.post(() -> {
                    trigger.setEnabled(true);
                    status.accept(message(error, "Could not load source images."));
                });
            }
        });
    }

    void put(JSONObject options, MediaItem source) throws JSONException {
        if (source == null) return;
        if (usesMatrix.get() && ("local".equals(source.source()) || "matrix".equals(source.source()))) {
            options.put("sourceImageUri", source.downloadUrl());
            options.put("sourceImageFileName", source.fileName());
            return;
        }
        options.put("imageId", source.id()).put("imageFileName", source.fileName());
    }

    private void captureLocal(boolean frontFacing, Consumer<MediaItem> onCaptured) {
        try {
            camera.capture(frontFacing, uri -> {
                String fileName = AndroidMediaStore.queryDisplayName(activity.getContentResolver(), uri);
                MediaItem item = new MediaItem(
                    "local-" + System.currentTimeMillis(), "image", fileName, fileName,
                    Long.toString(System.currentTimeMillis()), uri.toString(), uri.toString(),
                    "local", -1
                );
                onCaptured.accept(item);
                status.accept("Captured image ready for encrypted Matrix upload.");
            });
        } catch (Exception error) {
            status.accept(message(error, "Could not open the camera."));
        }
    }

    @SuppressWarnings("unchecked")
    private void replaceChoices(List<MediaItem> images, Spinner spinner, String emptyLabel) {
        StyledSpinnerAdapter<String> adapter = (StyledSpinnerAdapter<String>) spinner.getAdapter();
        adapter.clear();
        adapter.add(emptyLabel);
        for (MediaItem item : images) adapter.add(item.title().isEmpty() ? item.fileName() : item.title());
        adapter.notifyDataSetChanged();
        spinner.setSelection(0);
    }

    private String message(Exception error, String fallback) {
        return error.getMessage() == null ? fallback : error.getMessage();
    }
}
