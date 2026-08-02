package com.uragestudio.companion;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.function.Supplier;

final class MediaPreviewCache {
    private final File directory;
    private final Supplier<DashboardApi> dashboardApi;
    private final OfflineMediaStore offline;

    MediaPreviewCache(Context context, Supplier<DashboardApi> dashboardApi) {
        directory = new File(context.getCacheDir(), "media-previews");
        this.dashboardApi = dashboardApi;
        offline = new OfflineMediaStore(context);
    }

    File resolve(MediaItem item) throws Exception {
        if (item.downloadUrl().startsWith("file:")) {
            File local = new File(URI.create(item.downloadUrl()));
            if (!local.isFile()) throw new IllegalStateException("The local media file no longer exists.");
            return local;
        }
        File targetDirectory = offline.enabled() ? offline.fileFor(item).getParentFile() : directory;
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create the media preview cache.");
        }
        File cached = offline.enabled() ? offline.fileFor(item) : new File(directory, cacheName(item));
        if (cached.isFile() && cached.length() == item.size()) return cached;
        if (cached.isFile() && item.size() <= 0) return cached;
        DashboardApi api = dashboardApi.get();
        if (api == null) throw new IllegalStateException("Pair with the dashboard to preview this media.");
        File partial = new File(directory, cached.getName() + ".partial");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            api.download(item, output);
        }
        if (!partial.renameTo(cached)) {
            throw new IllegalStateException("Could not finish the cached media preview.");
        }
        offline.remember(item, cached);
        return cached;
    }

    private String cacheName(MediaItem item) {
        return (item.id() + "-" + item.fileName()).replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
