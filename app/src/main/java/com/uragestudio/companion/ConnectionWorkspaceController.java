package com.uragestudio.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.card.MaterialCardView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Composes connection-mode routing with focused LAN and Matrix configuration sections. */
final class ConnectionWorkspaceController {
    private final ConnectionRouteStore routeStore;
    private final MobileUiKit ui;
    private final Consumer<String> status;
    private final LanPairingSectionController lan;
    private final MatrixRelaySectionController matrix;
    private final StudioThemeSectionController theme;
    private final ScrollView view;
    private LinearLayout providerField;
    private TextView routeSummary;

    ConnectionWorkspaceController(
        Activity activity, ExecutorService executor, Handler main, Consumer<String> status,
        Runnable openGallery, Runnable refreshGallery
    ) {
        this.status = status;
        routeStore = new ConnectionRouteStore(activity);
        ui = new MobileUiKit(activity);
        lan = new LanPairingSectionController(activity, executor, main, status, openGallery, refreshGallery);
        matrix = new MatrixRelaySectionController(activity, status, lan::reportError);
        theme = new StudioThemeSectionController(activity, status);
        view = build(activity);
    }

    View view() { return view; }
    void show(boolean visible) { view.setVisibility(visible ? View.VISIBLE : View.GONE); }
    String activeRoute() { return routeStore.activeRoute(); }
    boolean hasDashboardPairing() { return lan.hasPairing(); }
    DashboardApi dashboardApi() { return lan.dashboardApi(); }
    MatrixSdkRelayClient matrixRelay() { return matrix.client(); }
    void discoverUnlessPairingIntent(Intent intent) { lan.discoverUnlessPairingIntent(intent); }
    boolean handlePairingIntent(Intent intent) { return lan.handlePairingIntent(intent); }
    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return lan.handleActivityResult(requestCode, resultCode, data);
    }
    void reportError(Exception error) { lan.reportError(error); }
    void setThemeSynchronizeAction(Runnable action) { theme.setSynchronizeAction(action); }

    private ScrollView build(Activity activity) {
        LinearLayout screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(20));
        screen.addView(ui.screenTitle("Connection"));
        TextView description = ui.body("Pair locally for fast transfers, then add Matrix for secure remote workflows.");
        description.setPadding(0, ui.dp(3), 0, ui.dp(10));
        screen.addView(description);
        screen.addView(buildRoutingCard(activity), ui.spacedMatchWrap());
        screen.addView(lan.view(), ui.spacedMatchWrap());
        screen.addView(matrix.view(), ui.spacedMatchWrap());
        screen.addView(theme.view(), ui.spacedMatchWrap());
        applyRouteVisibility(routeStore.usesMatrix());
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(screen);
        return scroll;
    }

    private MaterialCardView buildRoutingCard(Activity activity) {
        LinearLayout content = ui.cardContent();
        content.addView(ui.overline("Workflow routing"));
        content.addView(ui.sectionTitle("Connection mode"));
        content.addView(ui.body("This one choice controls Chat and every media Studio workflow."));
        Spinner mode = spinner(activity, List.of("LAN", "Internet"));
        content.addView(ui.overline("Mode"), ui.spacedMatchWrap());
        content.addView(mode, ui.spacedMatchWrap());
        providerField = new LinearLayout(activity);
        providerField.setOrientation(LinearLayout.VERTICAL);
        providerField.addView(ui.overline("Internet provider"), ui.spacedMatchWrap());
        providerField.addView(spinner(activity, List.of("Matrix")), ui.spacedMatchWrap());
        content.addView(providerField);
        routeSummary = ui.status("");
        content.addView(routeSummary, ui.spacedMatchWrap());
        mode.setSelection(routeStore.usesMatrix() ? 1 : 0);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean internet = position == 1;
                if (internet) routeStore.useMatrix(); else routeStore.useLan();
                applyRouteVisibility(internet);
                status.accept(internet
                    ? "All Studio workflows now use the Matrix Internet relay."
                    : "All Studio workflows now use the paired LAN dashboard.");
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        MaterialCardView card = ui.card();
        card.addView(content);
        return card;
    }

    private void applyRouteVisibility(boolean internet) {
        providerField.setVisibility(internet ? View.VISIBLE : View.GONE);
        lan.show(!internet);
        matrix.show(internet);
        routeSummary.setText(internet ? "Internet · Matrix encrypted relay" : "LAN · Direct dashboard");
    }

    private Spinner spinner(Activity activity, List<String> values) {
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new StyledSpinnerAdapter<>(activity, values));
        spinner.setBackground(ui.controlBackground());
        spinner.setMinimumHeight(ui.dp(52));
        return spinner;
    }
}
