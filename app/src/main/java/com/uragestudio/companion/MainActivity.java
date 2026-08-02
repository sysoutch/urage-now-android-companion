package com.uragestudio.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application shell. Feature controllers own Connection, Gallery, Chat, and media Studio behavior. */
public final class MainActivity extends Activity {
    private static final String STATE_WORKSPACE = "active_workspace";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MobileUiKit ui;
    private TextView status;
    private WorkspaceRailController navigation;
    private ConnectionWorkspaceController connection;
    private GalleryWorkspaceController gallery;
    private WorkflowWorkspaceController workflows;
    private DashboardThemeSynchronizer themeSynchronizer;
    private WorkflowJobRailBinder jobRailBinder;
    private String activeWorkspace = "connection";

    @Override
    protected void onCreate(Bundle state) {
        if (new StudioThemeStore(this).active().light()) {
            setTheme(R.style.Theme_URageCompanion_Light);
        }
        super.onCreate(state);
        ui = new MobileUiKit(this);
        setContentView(buildUi());
        applySystemTheme();
        themeSynchronizer = new DashboardThemeSynchronizer(
            this, executor, main,
            () -> connection.hasDashboardPairing() ? connection.dashboardApi() : null,
            this::setStatus
        );
        connection.setThemeSynchronizeAction(themeSynchronizer::refresh);
        requestNotificationPermission();
        String restoredWorkspace = state == null ? null : state.getString(STATE_WORKSPACE);
        navigation.select(restoredWorkspace == null
            ? (connection.hasDashboardPairing() ? "gallery" : "connection")
            : restoredWorkspace);
        connection.discoverUnlessPairingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        connection.handlePairingIntent(intent);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.backgroundColor());
        root.addView(buildHeader(), matchWrap());

        FrameLayout content = new FrameLayout(this);
        connection = new ConnectionWorkspaceController(
            this, executor, main, this::setStatus, this::selectGallery, () -> gallery.refresh()
        );
        gallery = new GalleryWorkspaceController(
            this, executor, main, connection::dashboardApi, this::setStatus, connection::reportError
        );
        workflows = new WorkflowWorkspaceController(
            this, executor, main, connection::dashboardApi, connection::matrixRelay,
            connection::activeRoute, this::setStatus, connection::reportError, gallery::refresh
        );
        content.addView(connection.view(), frameMatch());
        content.addView(gallery.view(), frameMatch());
        content.addView(workflows.chatView(), frameMatch());
        content.addView(workflows.imageView(), frameMatch());
        content.addView(workflows.audioView(), frameMatch());
        content.addView(workflows.musicView(), frameMatch());
        content.addView(workflows.videoView(), frameMatch());
        content.addView(workflows.model3dView(), frameMatch());

        navigation = new WorkspaceRailController(this, this::showWorkspace);
        jobRailBinder = new WorkflowJobRailBinder(this, main, navigation);
        if (navigation.usesTabletLayout()) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(navigation.view(), new LinearLayout.LayoutParams(ui.dp(108), LinearLayout.LayoutParams.MATCH_PARENT));
            body.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(navigation.view(), matchWrap());
        }
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(10));
        header.addView(ui.appTitle("URage Companion"));
        header.addView(ui.body("Create, transfer, and continue away from your desk."));
        status = ui.status("Not connected");
        header.addView(status, ui.spacedMatchWrap());
        return header;
    }

    private void showWorkspace(String workspace) {
        activeWorkspace = workspace;
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        gallery.show("gallery".equals(workspace));
        connection.show("connection".equals(workspace));
        workflows.show(workspace);
    }

    private void selectGallery() {
        main.post(() -> {
            navigation.select("gallery");
            themeSynchronizer.refresh();
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (connection.handleActivityResult(requestCode, resultCode, data)) return;
        if (workflows.handleActivityResult(requestCode, resultCode)) return;
        super.onActivityResult(requestCode, resultCode, data);
        gallery.handleActivityResult(requestCode, resultCode, data);
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private FrameLayout.LayoutParams frameMatch() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void applySystemTheme() {
        getWindow().setStatusBarColor(ui.backgroundColor());
        getWindow().setNavigationBarColor(ui.backgroundColor());
        if (Build.VERSION.SDK_INT >= 30) {
            int appearance = ui.usesLightSystemBars()
                ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                : 0;
            getWindow().getInsetsController().setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            );
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString(STATE_WORKSPACE, activeWorkspace);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        jobRailBinder.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (themeSynchronizer != null) themeSynchronizer.refresh();
    }

    @Override
    protected void onStop() {
        jobRailBinder.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        jobRailBinder.stop();
        gallery.close();
        workflows.close();
        executor.shutdownNow();
        super.onDestroy();
    }
}
