package com.uragestudio.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
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
    private HomeWorkspaceController home;
    private ConnectionWorkspaceController connection;
    private GalleryWorkspaceController gallery;
    private WorkflowWorkspaceController workflows;
    private ToolsWorkspaceController tools;
    private DashboardThemeSynchronizer themeSynchronizer;
    private WorkflowJobRailBinder jobRailBinder;
    private String activeWorkspace = "home";

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
            ? "home"
            : restoredWorkspace);
        handleWorkflowNotificationIntent(getIntent());
        connection.discoverUnlessPairingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWorkflowNotificationIntent(intent);
        connection.handlePairingIntent(intent);
    }

    private void handleWorkflowNotificationIntent(Intent intent) {
        if (intent == null || !"com.uragestudio.companion.OPEN_GENERATION".equals(intent.getAction())) return;
        MediaItem item = new MediaItem(
            intent.getStringExtra("generationId"), intent.getStringExtra("generationKind"),
            intent.getStringExtra("generationFileName"), intent.getStringExtra("generationTitle"),
            intent.getStringExtra("generationCreatedAt"), intent.getStringExtra("generationDownloadUrl"),
            intent.getStringExtra("generationThumbnailUrl"), intent.getStringExtra("generationSource"),
            intent.getLongExtra("generationSize", -1)
        );
        if (item.id().isBlank() || item.kind().isBlank()) return;
        main.post(() -> {
            navigation.select("gallery");
            gallery.showGeneratedPreview(item);
        });
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.backgroundColor());
        FrameLayout content = new FrameLayout(this);
        connection = new ConnectionWorkspaceController(
            this, executor, main, this::setStatus, this::selectGallery, () -> gallery.refresh()
        );
        gallery = new GalleryWorkspaceController(
            this, executor, main, connection::dashboardApi, this::setStatus, connection::reportError,
            this::generateModelFromImage
        );
        workflows = new WorkflowWorkspaceController(
            this, executor, main, connection::dashboardApi, connection::matrixRelay,
            connection::activeRoute, this::setStatus, connection::reportError, gallery::refresh,
            this::generateModelFromImage
        );
        tools = new ToolsWorkspaceController(
            this, executor, main, connection::dashboardApi, this::setStatus, connection::reportError
        );
        home = new HomeWorkspaceController(this, ui, workspace -> navigation.select(workspace));
        status = home.statusView();
        content.addView(home.view(), frameMatch());
        content.addView(connection.view(), frameMatch());
        content.addView(gallery.view(), frameMatch());
        content.addView(workflows.chatView(), frameMatch());
        content.addView(workflows.imageView(), frameMatch());
        content.addView(workflows.audioView(), frameMatch());
        content.addView(workflows.musicView(), frameMatch());
        content.addView(workflows.videoView(), frameMatch());
        content.addView(workflows.model3dView(), frameMatch());
        content.addView(tools.view(), frameMatch());

        boolean portraitNavigation = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        navigation = new WorkspaceRailController(this, this::showWorkspace, portraitNavigation);
        jobRailBinder = new WorkflowJobRailBinder(this, main, navigation);
        if (portraitNavigation) {
            root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(navigation.view(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(68)));
        } else {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(navigation.view(), new LinearLayout.LayoutParams(ui.dp(92), LinearLayout.LayoutParams.MATCH_PARENT));
            body.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }
        return root;
    }

    private void showWorkspace(String workspace) {
        activeWorkspace = workspace;
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        home.show("home".equals(workspace));
        gallery.show("gallery".equals(workspace));
        connection.show("connection".equals(workspace));
        tools.show("tools".equals(workspace));
        workflows.show(workspace);
    }

    private void selectGallery() {
        main.post(() -> {
            navigation.select("gallery");
            themeSynchronizer.refresh();
        });
    }

    private void generateModelFromImage(MediaItem image) {
        workflows.selectModel3dSource(image);
        navigation.select("model3d");
        setStatus("Selected image for 3D generation.");
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
        // Route audio pick result through WorkflowWorkspaceController → ChatWorkspaceController
        boolean handled = workflows.handleActivityResult(requestCode, resultCode, data);
        if (!handled && tools.handleActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
        gallery.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        workflows.onRequestPermissionsResult(requestCode, permissions, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private FrameLayout.LayoutParams frameMatch() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
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
        tools.close();
        executor.shutdownNow();
        super.onDestroy();
    }
}
