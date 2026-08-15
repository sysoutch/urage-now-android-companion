package com.uragestudio.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns Gallery presentation, pagination, transfer actions, and rich media previews. */
final class GalleryWorkspaceController {
    static final int PICK_MEDIA = 7;
    private static final int PAGE_SIZE = 18;

    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final Supplier<DashboardApi> dashboardApi;
    private final Consumer<String> status;
    private final Consumer<Exception> errors;
    private final Consumer<MediaItem> generateModelFromImage;
    private final MobileUiKit ui;
    private final MediaItemAdapter mediaAdapter;
    private final MediaPreviewController previews;
    private final OfflineMediaStore offline;
    private final LinearLayout view;
    private Spinner kindSpinner;
    private Button loadMoreButton;
    private String nextCursor;

    GalleryWorkspaceController(
        Activity activity, ExecutorService executor, Handler main,
        Supplier<DashboardApi> dashboardApi, Consumer<String> status, Consumer<Exception> errors,
        Consumer<MediaItem> generateModelFromImage
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.dashboardApi = dashboardApi;
        this.status = status;
        this.errors = errors;
        this.generateModelFromImage = generateModelFromImage;
        ui = new MobileUiKit(activity);
        mediaAdapter = new MediaItemAdapter(activity);
        offline = new OfflineMediaStore(activity);
        previews = new MediaPreviewController(
            activity, executor, main, new MediaPreviewCache(activity, dashboardApi), errors, generateModelFromImage
        );
        view = build();
    }

    View view() {
        return view;
    }

    void show(boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) refresh();
    }

    void refresh() {
        nextCursor = null;
        loadPage(false);
    }

    void close() {
        mediaAdapter.close();
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_MEDIA) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        queueUpload(data.getData());
        return true;
    }

    private LinearLayout build() {
        LinearLayout screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setVisibility(View.GONE);
        screen.setPadding(dp(16), dp(8), dp(16), dp(8));
        screen.addView(ui.screenTitle("Gallery"));
        screen.addView(ui.body("Preview recent dashboard media and downloaded Matrix results."));

        LinearLayout mediaRow = row();
        kindSpinner = new Spinner(activity);
        kindSpinner.setAdapter(new StyledSpinnerAdapter<>(activity, List.of("image", "video", "audio", "model3d")));
        kindSpinner.setPadding(dp(10), dp(3), dp(10), dp(3));
        kindSpinner.setBackground(ui.controlBackground());
        mediaRow.addView(kindSpinner, weighted());
        Button refresh = ui.button("Refresh", MobileUiKit.ActionStyle.QUIET);
        refresh.setOnClickListener(ignored -> refresh());
        mediaRow.addView(refresh);
        Button upload = ui.button("Upload", MobileUiKit.ActionStyle.PRIMARY);
        upload.setOnClickListener(ignored -> pickUpload());
        mediaRow.addView(upload);
        Button settings = ui.button("Settings", MobileUiKit.ActionStyle.QUIET);
        settings.setOnClickListener(ignored -> showSettings());
        mediaRow.addView(settings);
        screen.addView(mediaRow, ui.spacedMatchWrap());

        EditText search = ui.input("Search this gallery");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                mediaAdapter.filter(value.toString());
            }
            public void afterTextChanged(Editable value) {}
        });
        screen.addView(search, ui.spacedMatchWrap());

        GridView grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(mediaAdapter);
        grid.setOnItemClickListener((parent, itemView, position, id) -> previews.show(mediaAdapter.itemAt(position)));
        grid.setOnItemLongClickListener((parent, itemView, position, id) -> {
            showActions(mediaAdapter.itemAt(position));
            return true;
        });
        TextView empty = ui.body("Your gallery is empty.\nPair a dashboard, then refresh or upload media to get started.");
        empty.setGravity(Gravity.CENTER);
        empty.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_gallery, 0, 0);
        empty.setCompoundDrawablePadding(dp(14));
        FrameLayout galleryContent = new FrameLayout(activity);
        galleryContent.addView(grid, frameMatch());
        galleryContent.addView(empty, frameMatch());
        grid.setEmptyView(empty);
        screen.addView(galleryContent, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        loadMoreButton = ui.button("Load more", MobileUiKit.ActionStyle.SECONDARY);
        loadMoreButton.setVisibility(View.GONE);
        loadMoreButton.setOnClickListener(ignored -> loadPage(true));
        screen.addView(loadMoreButton, ui.spacedMatchWrap());
        return screen;
    }

    private void loadPage(boolean append) {
        DashboardApi api = dashboardApi.get();
        String kind = kindSpinner.getSelectedItem().toString();
        String cursor = append ? nextCursor : null;
        status.accept(append ? "Loading more…" : "Loading latest " + kind + " media…");
        loadMoreButton.setEnabled(false);
        executor.execute(() -> {
            try {
                List<MediaItem> local = append ? List.of() : localMedia(kind);
                DashboardApi.MediaPage page = api == null
                    ? new DashboardApi.MediaPage(List.of(), 0, null)
                    : api.listMedia(kind, cursor, PAGE_SIZE);
                List<MediaItem> combined = mergeMedia(local, page.items());
                main.post(() -> {
                    if (append) mediaAdapter.append(combined, api);
                    else mediaAdapter.replace(combined, api);
                    nextCursor = page.nextCursor();
                    loadMoreButton.setVisibility(nextCursor == null ? View.GONE : View.VISIBLE);
                    loadMoreButton.setEnabled(true);
                    status.accept("Showing " + mediaAdapter.getCount() + " " + kind + " item(s).");
                });
            } catch (Exception error) {
                main.post(() -> {
                    loadMoreButton.setEnabled(true);
                    status.accept("Gallery refresh failed: " + (error.getMessage() == null ? "unknown error" : error.getMessage()));
                });
                errors.accept(error);
            }
        });
    }

    private List<MediaItem> localMedia(String kind) {
        List<MediaItem> local = new ArrayList<>(new MatrixMediaGalleryStore(activity).list(kind));
        local.addAll(offline.list(kind));
        return local;
    }

    private List<MediaItem> mergeMedia(List<MediaItem> local, List<MediaItem> remote) {
        Map<String, MediaItem> unique = new LinkedHashMap<>();
        for (MediaItem item : local) unique.put(item.kind() + ":" + item.id() + ":" + item.fileName(), item);
        for (MediaItem item : remote) unique.put(item.kind() + ":" + item.id() + ":" + item.fileName(), item);
        return new ArrayList<>(unique.values());
    }

    private void showSettings() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        android.widget.CheckBox cache = new android.widget.CheckBox(activity);
        cache.setText("Keep opened media for offline viewing");
        cache.setTextColor(ui.textColor());
        cache.setChecked(offline.enabled());
        content.addView(cache, ui.spacedMatchWrap());
        content.addView(ui.body(
            "When enabled, media you open is copied into durable app storage and appears in Gallery without a dashboard connection."
        ), ui.spacedMatchWrap());
        new AlertDialog.Builder(activity)
            .setTitle("Gallery settings")
            .setView(content)
            .setPositiveButton("Save", (dialog, which) -> {
                offline.setEnabled(cache.isChecked());
                refresh();
            })
            .setNeutralButton("Clear offline media", (dialog, which) -> {
                offline.clear();
                refresh();
                status.accept("Offline Gallery media cleared.");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showActions(MediaItem item) {
        List<String> actions = new ArrayList<>();
        actions.add("Download");
        if ("image".equals(item.kind())) actions.add("Generate 3D Model");
        if (canOpenInBambuStudio(item)) {
            actions.add("Send to BambuLab");
            actions.add("Send to BambuLab + Print");
        }
        if (!"matrix".equals(item.source())) {
            if ("upload".equals(item.source())) actions.add("Rename title");
            actions.add("Delete");
        }
        new AlertDialog.Builder(activity).setTitle(title(item))
            .setItems(actions.toArray(new String[0]), (dialog, index) -> {
                String action = actions.get(index);
                if ("Download".equals(action)) download(item);
                else if ("Generate 3D Model".equals(action)) generateModelFromImage.accept(item);
                else if ("Send to BambuLab".equals(action)) openInBambuStudio(item);
                else if ("Send to BambuLab + Print".equals(action)) {
                    status.accept("BambuLab + Print needs a configured slicing preset and printer transport. The dashboard currently only opens Bambu Studio.");
                }
                else if ("Rename title".equals(action)) promptRename(item);
                else confirmDelete(item);
            })
            .setNegativeButton("Cancel", null).show();
    }

    private boolean canOpenInBambuStudio(MediaItem item) {
        return "model3d".equals(item.kind()) && "generated".equals(item.source())
            && item.id() != null && !item.id().isBlank() && item.fileName() != null && !item.fileName().isBlank();
    }

    private void openInBambuStudio(MediaItem item) {
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
                errors.accept(error);
            }
        });
    }

    private void promptRename(MediaItem item) {
        EditText title = ui.input("Media title");
        title.setText(item.title());
        new AlertDialog.Builder(activity).setTitle("Rename media").setView(title)
            .setPositiveButton("Save", (dialog, which) -> executor.execute(() -> {
                try {
                    DashboardApi api = dashboardApi.get();
                    if (api == null) return;
                    api.updateMediaTitle(item, title.getText().toString());
                    main.post(this::refresh);
                } catch (Exception error) {
                    errors.accept(error);
                }
            }))
            .setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(MediaItem item) {
        new AlertDialog.Builder(activity).setTitle("Delete media?")
            .setMessage("This permanently removes " + item.fileName() + " from the dashboard.")
            .setPositiveButton("Delete", (dialog, which) -> executor.execute(() -> {
                try {
                    DashboardApi api = dashboardApi.get();
                    if (api == null) return;
                    api.deleteMedia(item);
                    main.post(this::refresh);
                } catch (Exception error) {
                    errors.accept(error);
                }
            }))
            .setNegativeButton("Cancel", null).show();
    }

    private void pickUpload() {
        if (dashboardApi.get() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(intent, PICK_MEDIA);
    }

    private void queueUpload(Uri uri) {
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        String kind = kindSpinner.getSelectedItem().toString();
        String fileName = AndroidMediaStore.queryDisplayName(activity.getContentResolver(), uri);
        String contentType = activity.getContentResolver().getType(uri);
        long totalSize = AndroidMediaStore.querySize(activity.getContentResolver(), uri);
        if (dashboardApi.get() == null) return;
        try {
            BackgroundUploadScheduler.enqueue(activity, uri, kind, fileName, contentType, totalSize);
            status.accept("Resumable background upload queued for " + fileName + ".");
        } catch (Exception error) {
            errors.accept(error);
        }
    }

    private void download(MediaItem item) {
        if ("matrix".equals(item.source()) && item.downloadUrl().startsWith("file:")) {
            try {
                File source = new File(URI.create(item.downloadUrl()));
                AndroidMediaStore.saveDownload(activity.getContentResolver(), item.fileName(), "application/octet-stream", source);
                status.accept("Saved " + item.fileName() + " to Downloads/URage NOW.");
            } catch (Exception error) {
                errors.accept(error);
            }
            return;
        }
        DashboardApi api = dashboardApi.get();
        if (api == null) return;
        try {
            BackgroundDownloadScheduler.enqueue(activity, api, item);
            status.accept("Download queued for " + item.fileName() + ".");
        } catch (Exception error) {
            errors.accept(error);
        }
    }

    private String title(MediaItem item) {
        return item.title().isEmpty() ? item.fileName() : item.title();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private FrameLayout.LayoutParams frameMatch() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return ui.dp(value);
    }
}
