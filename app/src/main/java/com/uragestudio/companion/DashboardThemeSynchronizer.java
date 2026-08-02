package com.uragestudio.companion;

import android.app.Activity;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Refreshes the cached dashboard theme without weakening the local override policy. */
final class DashboardThemeSynchronizer {
    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final Supplier<DashboardApi> dashboardApi;
    private final Consumer<String> status;
    private final StudioThemeStore themes;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    DashboardThemeSynchronizer(
        Activity activity, ExecutorService executor, Handler main,
        Supplier<DashboardApi> dashboardApi, Consumer<String> status
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.dashboardApi = dashboardApi;
        this.status = status;
        themes = new StudioThemeStore(activity);
    }

    void refresh() {
        if (!themes.usesDashboardTheme() || !inFlight.compareAndSet(false, true)) return;
        DashboardApi api = dashboardApi.get();
        if (api == null) {
            inFlight.set(false);
            return;
        }
        executor.execute(() -> {
            try {
                DashboardApi.DashboardTheme dashboardTheme = api.getTheme();
                boolean changed = themes.cacheDashboardTheme(dashboardTheme.theme());
                main.post(() -> {
                    inFlight.set(false);
                    if (!changed || activity.isFinishing() || activity.isDestroyed()) return;
                    status.accept("Following dashboard theme: " + themes.dashboardTheme().label() + ".");
                    activity.recreate();
                });
            } catch (Exception ignored) {
                inFlight.set(false);
                // The last synchronized theme is an intentional offline fallback.
            }
        });
    }
}
