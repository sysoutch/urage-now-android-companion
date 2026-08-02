package com.uragestudio.companion;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compact, media-aware latest-result card shared by every mobile Studio. */
final class StudioWorkflowResultView extends LinearLayout {
    private final Activity activity;
    private final MobileUiKit ui;
    private final ImageView preview;
    private final TextView title;
    private final TextView detail;
    private android.widget.Button action;
    private Model3dPreviewView modelPreview;
    private String boundThumbnail = "";
    private MediaItem displayedItem;

    StudioWorkflowResultView(Activity activity, MobileUiKit ui, String emptyText) {
        super(activity);
        this.activity = activity;
        this.ui = ui;
        setOrientation(VERTICAL);
        setGravity(Gravity.START);
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        setBackground(ui.controlBackground());

        preview = new ImageView(activity);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setVisibility(View.GONE);
        addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(190)));

        title = ui.body(emptyText);
        title.setTextColor(ui.textColor());
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        addView(title, ui.spacedMatchWrap());

        detail = ui.body("");
        detail.setVisibility(View.GONE);
        addView(detail, ui.spacedMatchWrap());
    }

    void showStatus(String value) {
        displayedItem = null;
        boundThumbnail = "";
        preview.setVisibility(View.GONE);
        if (modelPreview != null) modelPreview.setVisibility(View.GONE);
        if (action != null) action.setVisibility(View.GONE);
        title.setText(value);
        detail.setVisibility(View.GONE);
        setClickable(false);
    }

    void showResult(
        MediaItem item, Supplier<DashboardApi> dashboardApi,
        ExecutorService executor, Handler main
    ) {
        displayedItem = item;
        if (action != null) action.setVisibility(View.VISIBLE);
        title.setText(item.title() == null || item.title().isBlank() ? item.fileName() : item.title());
        boolean model3d = "model3d".equals(item.kind());
        detail.setText(item.kind().toUpperCase(Locale.ROOT) + "  ·  " + item.fileName()
            + (model3d
                ? "\nDrag to orbit, pinch to zoom, or tap the card for a larger preview."
                : "\nTap to open the result."));
        detail.setVisibility(View.VISIBLE);
        if (model3d) {
            preview.setVisibility(View.GONE);
            modelPreview().setVisibility(View.VISIBLE);
        } else {
            if (modelPreview != null) modelPreview.setVisibility(View.GONE);
            bindPreview(item, dashboardApi, executor, main);
        }
    }

    void setAction(String label, Consumer<MediaItem> callback) {
        if (action == null) {
            action = ui.button(label, MobileUiKit.ActionStyle.SECONDARY);
            action.setVisibility(View.GONE);
            addView(action, ui.spacedMatchWrap());
        } else {
            action.setText(label);
        }
        action.setOnClickListener(ignored -> {
            if (displayedItem != null) callback.accept(displayedItem);
        });
    }

    Model3dPreviewView modelPreview() {
        if (modelPreview == null) {
            modelPreview = new Model3dPreviewView(activity);
            addView(modelPreview, 0, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(250)));
        }
        return modelPreview;
    }

    private void bindPreview(
        MediaItem item, Supplier<DashboardApi> dashboardApi,
        ExecutorService executor, Handler main
    ) {
        String thumbnail = item.thumbnailUrl() == null ? "" : item.thumbnailUrl();
        boundThumbnail = thumbnail;
        preview.setVisibility(View.VISIBLE);
        preview.setScaleType(ImageView.ScaleType.CENTER);
        preview.setImageResource(iconFor(item.kind()));
        if (thumbnail.isBlank()) return;

        executor.execute(() -> {
            try {
                Bitmap bitmap;
                if (thumbnail.startsWith("file:")) {
                    bitmap = BitmapFactory.decodeFile(new File(URI.create(thumbnail)).getAbsolutePath());
                } else {
                    DashboardApi api = dashboardApi.get();
                    if (api == null) return;
                    byte[] bytes = api.downloadBytes(thumbnail);
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
                if (bitmap == null) return;
                main.post(() -> {
                    if (!thumbnail.equals(boundThumbnail)) return;
                    preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    preview.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // The type-specific placeholder remains useful if a preview cannot be fetched.
            }
        });
    }

    private int iconFor(String kind) {
        return switch (kind) {
            case "image" -> R.drawable.ic_image;
            case "video" -> R.drawable.ic_video;
            case "audio", "music" -> R.drawable.ic_audio;
            case "model3d" -> R.drawable.ic_cube;
            default -> R.drawable.ic_gallery;
        };
    }
}
