package com.uragestudio.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;

public final class MediaItemAdapter extends BaseAdapter {
    private final Context context;
    private final List<MediaItem> allItems = new ArrayList<>();
    private final List<MediaItem> visibleItems = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<>(24);
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MobileUiKit ui;
    private DashboardApi api;
    private String query = "";

    public MediaItemAdapter(Context context) {
        this.context = context;
        this.ui = new MobileUiKit(context);
    }

    public void replace(List<MediaItem> items, DashboardApi api) {
        this.api = api;
        allItems.clear();
        allItems.addAll(items);
        applyFilter();
    }

    public void append(List<MediaItem> items, DashboardApi api) {
        this.api = api;
        allItems.addAll(items);
        applyFilter();
    }

    public void filter(String value) {
        query = String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public MediaItem itemAt(int position) {
        return visibleItems.get(position);
    }

    private void applyFilter() {
        visibleItems.clear();
        for (MediaItem item : allItems) {
            String searchable = (item.title() + " " + item.fileName() + " " + item.kind() + " " + item.source()).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || searchable.contains(query)) visibleItems.add(item);
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return visibleItems.size(); }
    @Override public MediaItem getItem(int position) { return visibleItems.get(position); }
    @Override public long getItemId(int position) { return getItem(position).id().hashCode(); }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
        MediaItem item = getItem(position);
        LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : createTile();
        ImageView image = (ImageView) row.getChildAt(0);
        ((TextView) row.getChildAt(1)).setText(item.title().isEmpty() ? item.fileName() : item.title());
        String size = item.size() > 0 ? " · " + formatBytes(item.size()) : "";
        ((TextView) row.getChildAt(2)).setText(item.kind().toUpperCase(Locale.ROOT) + " · " + item.source() + size);
        bindThumbnail(image, item);
        return row;
    }

    private LinearLayout createTile() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(12));
        row.setBackground(ui.controlBackground());
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)));
        TextView title = label(14, ui.textColor());
        title.setPadding(0, dp(7), 0, 0);
        title.setMaxLines(1);
        row.addView(title);
        row.addView(label(11, ui.textMutedColor()));
        return row;
    }

    private TextView label(int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setMaxLines(2);
        return view;
    }

    private void bindThumbnail(ImageView view, MediaItem item) {
        String url = item.thumbnailUrl();
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageResource(R.drawable.ic_gallery);
            return;
        }
        Bitmap cached = thumbnails.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.ic_gallery);
        if (url.startsWith("file:")) {
            loader.execute(() -> {
                try {
                    Bitmap bitmap = BitmapFactory.decodeFile(new File(java.net.URI.create(url)).getAbsolutePath());
                    if (bitmap == null) return;
                    thumbnails.put(url, bitmap);
                    main.post(() -> {
                        if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
                    });
                } catch (Exception ignored) {
                    // Keep the placeholder for unreadable local media.
                }
            });
            return;
        }
        if (api == null) return;
        DashboardApi currentApi = api;
        loader.execute(() -> {
            try {
                Bitmap bitmap = downloadBitmap(currentApi, url);
                if (bitmap == null && item.downloadUrl() != null && !item.downloadUrl().isBlank()) {
                    bitmap = downloadBitmap(currentApi, item.downloadUrl());
                }
                if (bitmap == null) return;
                Bitmap resolvedBitmap = bitmap;
                thumbnails.put(url, bitmap);
                main.post(() -> {
                    if (url.equals(view.getTag())) view.setImageBitmap(resolvedBitmap);
                });
            } catch (Exception ignored) {
                // The type placeholder remains when a thumbnail cannot be loaded.
            }
        });
    }

    private Bitmap downloadBitmap(DashboardApi api, String url) {
        try {
            byte[] bytes = api.downloadBytes(url);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void close() {
        loader.shutdownNow();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
