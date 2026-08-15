package com.uragestudio.companion;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;
import com.google.android.material.card.MaterialCardView;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Owns dashboard discovery, connection diagnostics, pairing, and pairing QR handling. */
final class LanPairingSectionController {
    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final Consumer<String> status;
    private final Runnable openGallery;
    private final Runnable refreshGallery;
    private final SecurePairingStore pairingStore;
    private final MobileUiKit ui;
    private final List<DashboardInstance> instances = new ArrayList<>();
    private final MaterialCardView view;
    private StyledSpinnerAdapter<DashboardInstance> instanceAdapter;
    private Spinner instanceSpinner;
    private EditText manualHost;
    private EditText pairingCode;
    private EditText certificatePin;

    LanPairingSectionController(
        Activity activity, ExecutorService executor, Handler main, Consumer<String> status,
        Runnable openGallery, Runnable refreshGallery
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.status = status;
        this.openGallery = openGallery;
        this.refreshGallery = refreshGallery;
        pairingStore = new SecurePairingStore(activity);
        ui = new MobileUiKit(activity);
        view = build();
        restorePairing();
    }

    View view() { return view; }
    void show(boolean visible) { view.setVisibility(visible ? View.VISIBLE : View.GONE); }
    boolean hasPairing() { return pairingStore.load() != null; }

    DashboardApi dashboardApi() {
        SecurePairingStore.Pairing pairing = pairingStore.load();
        if (pairing == null || pairing.baseUrl().isBlank() || pairing.token().isBlank()) {
            status.accept("Pair with a dashboard first.");
            return null;
        }
        return new DashboardApi(pairing.baseUrl(), pairing.token(), pairing.certificateSha256());
    }

    void discoverUnlessPairingIntent(Intent intent) {
        if (!handlePairingIntent(intent)) discover();
    }

    boolean handlePairingIntent(Intent intent) {
        return handlePairingUri(intent == null ? null : intent.getData());
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scan == null) return false;
        String contents = scan.getContents();
        if (contents == null) {
            status.accept("Pairing QR scan cancelled.");
            return true;
        }
        if (!handlePairingUri(Uri.parse(contents.trim()))) {
            status.accept("This is not an Android pairing QR. In Dashboard Settings > Network > Devices, choose Create Pairing QR.");
        }
        return true;
    }

    void reportError(Exception error) {
        main.post(() -> {
            String message = message(error, "Operation failed.");
            status.accept(message);
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    private MaterialCardView build() {
        LinearLayout content = ui.cardContent();
        content.addView(ui.overline("Local network"));
        content.addView(ui.sectionTitle("Dashboard pairing"));
        content.addView(ui.body("Discover a dashboard on this Wi-Fi, or enter the address shown under Settings › Network."));
        instanceSpinner = new Spinner(activity);
        instances.add(new DashboardInstance("No dashboard discovered yet", "", ""));
        instanceAdapter = new StyledSpinnerAdapter<>(activity, instances);
        instanceSpinner.setAdapter(instanceAdapter);
        instanceSpinner.setPadding(dp(10), dp(4), dp(10), dp(4));
        instanceSpinner.setMinimumHeight(dp(52));
        instanceSpinner.setBackground(ui.controlBackground());
        content.addView(ui.overline("Discovered dashboard"), ui.spacedMatchWrap());
        content.addView(instanceSpinner, ui.spacedMatchWrap());
        LinearLayout discovery = row();
        Button scan = ui.button("Scan network", MobileUiKit.ActionStyle.SECONDARY);
        scan.setOnClickListener(ignored -> discover());
        discovery.addView(scan, weighted());
        Button test = ui.button("Test address", MobileUiKit.ActionStyle.QUIET);
        test.setOnClickListener(ignored -> testConnection());
        discovery.addView(test, weighted());
        content.addView(discovery, ui.spacedMatchWrap());
        manualHost = ui.input("192.168.1.20:4782");
        certificatePin = ui.input("sha256/…");
        content.addView(ui.field("Dashboard address", "Use HTTPS when the dashboard has a trusted certificate.", manualHost), ui.spacedMatchWrap());
        content.addView(ui.field("Certificate pin", "Optional additional HTTPS certificate verification.", certificatePin), ui.spacedMatchWrap());
        pairingCode = ui.input("6-digit code");
        pairingCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout pairRow = row();
        pairRow.addView(pairingCode, weighted());
        Button pair = ui.button("Pair dashboard", MobileUiKit.ActionStyle.PRIMARY);
        pair.setOnClickListener(ignored -> pair());
        pairRow.addView(pair);
        content.addView(ui.overline("Pairing code"), ui.spacedMatchWrap());
        content.addView(pairRow, ui.spacedMatchWrap());
        Button qr = ui.button("Scan pairing QR", MobileUiKit.ActionStyle.SECONDARY);
        qr.setOnClickListener(ignored -> scanQr());
        content.addView(qr, ui.spacedMatchWrap());
        Button forget = ui.button("Forget dashboard pairing", MobileUiKit.ActionStyle.DANGER);
        forget.setOnClickListener(ignored -> {
            pairingStore.clear();
            status.accept("Saved dashboard pairing removed.");
        });
        content.addView(forget, ui.spacedMatchWrap());
        MaterialCardView card = ui.card();
        card.addView(content);
        return card;
    }

    private void discover() {
        status.accept("Scanning LAN…");
        executor.execute(() -> {
            try {
                WifiManager wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                List<DashboardInstance> found = new LanDiscoveryClient(wifi).discover(1_800);
                main.post(() -> {
                    instances.clear();
                    instances.addAll(found.isEmpty()
                        ? List.of(new DashboardInstance("No dashboard discovered yet", "", ""))
                        : found);
                    instanceAdapter.notifyDataSetChanged();
                    status.accept(found.isEmpty()
                        ? "No dashboard found. On the PC open Settings > Network, use LAN mode, Save & Apply, then check Private-network firewall access."
                        : "Select a dashboard to pair.");
                });
            } catch (Exception error) {
                reportError(error);
            }
        });
    }

    private void testConnection() {
        String baseUrl = selectedBaseUrl();
        if (baseUrl.isEmpty()) {
            status.accept("Enter the LAN URL shown in Dashboard Settings > Network.");
            return;
        }
        status.accept("Testing " + baseUrl + "…");
        executor.execute(() -> {
            try {
                DashboardApi.DashboardInfo info = new DashboardApi(baseUrl, "", selectedPin()).getInfo();
                main.post(() -> status.accept("Connected to " + info.name() + " (companion protocol " + info.protocol() + "). Enter the pairing code from Dashboard Settings."));
            } catch (Exception error) {
                reportConnectionError(error, baseUrl);
            }
        });
    }

    private void pair() {
        String baseUrl = selectedBaseUrl();
        String code = pairingCode.getText().toString().trim();
        if (baseUrl.isBlank() || code.length() != 6) {
            status.accept("Choose a dashboard and enter its 6-digit console pairing code.");
            return;
        }
        pairWithCredential(baseUrl, code, selectedPin(), false);
    }

    private void pairWithCredential(String baseUrl, String credential, String pin, boolean temporaryToken) {
        status.accept("Pairing…");
        executor.execute(() -> {
            try {
                DashboardApi api = new DashboardApi(baseUrl, "", pin);
                DashboardApi.Pairing paired = temporaryToken
                    ? api.pairToken(credential, android.os.Build.MODEL)
                    : api.pair(credential, android.os.Build.MODEL);
                pairingStore.save(baseUrl, paired.token(), pin);
                main.post(() -> {
                    status.accept("Paired with " + baseUrl);
                    openGallery.run();
                    refreshGallery.run();
                });
            } catch (Exception error) {
                reportError(error);
            }
        });
    }

    private boolean handlePairingUri(Uri data) {
        if (data == null || !"urage".equalsIgnoreCase(data.getScheme()) || !"pair".equalsIgnoreCase(data.getHost())) return false;
        String baseUrl = ConnectionAddressNormalizer.normalize(data.getQueryParameter("baseUrl"));
        String token = data.getQueryParameter("token");
        String pin = String.valueOf(data.getQueryParameter("certificateSha256") == null ? "" : data.getQueryParameter("certificateSha256"));
        String expiresAt = data.getQueryParameter("expiresAt");
        if (baseUrl.isEmpty() || token == null || token.isEmpty()) {
            status.accept("This pairing QR is incomplete.");
            return true;
        }
        try {
            if (expiresAt != null && java.time.Instant.parse(expiresAt).isBefore(java.time.Instant.now())) {
                status.accept("This pairing QR has expired. Create a fresh QR in Dashboard Settings.");
                return true;
            }
        } catch (Exception ignored) {
            status.accept("This pairing QR has an invalid expiry.");
            return true;
        }
        manualHost.setText(baseUrl);
        certificatePin.setText(pin);
        pairWithCredential(baseUrl, token, pin, true);
        return true;
    }

    private void scanQr() {
        new IntentIntegrator(activity)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the one-use pairing QR from Dashboard Settings > Network > Devices")
            .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false).initiateScan();
    }

    private void restorePairing() {
        SecurePairingStore.Pairing pairing = pairingStore.load();
        if (pairing == null || pairing.baseUrl().isBlank()) return;
        manualHost.setText(pairing.baseUrl());
        certificatePin.setText(pairing.certificateSha256());
    }

    private String selectedBaseUrl() {
        String manual = manualHost.getText().toString().trim();
        if (!manual.isEmpty()) return ConnectionAddressNormalizer.normalize(manual);
        Object selected = instanceSpinner.getSelectedItem();
        return selected instanceof DashboardInstance instance ? instance.baseUrl() : "";
    }

    private String selectedPin() {
        String manual = certificatePin.getText().toString().trim();
        if (!manual.isEmpty()) return manual;
        Object selected = instanceSpinner.getSelectedItem();
        return selected instanceof DashboardInstance instance ? instance.certificateSha256() : "";
    }

    private void reportConnectionError(Exception error, String baseUrl) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String result;
        if (cause instanceof UnknownHostException) {
            result = "That dashboard address is invalid or cannot be resolved: " + baseUrl;
        } else if (cause instanceof ConnectException || cause instanceof SocketTimeoutException) {
            result = "Cannot reach " + baseUrl + ". Enable LAN mode with Save & Apply, keep both devices on the same network, and allow the Private-network firewall rules.";
        } else {
            result = message(error, "Dashboard connection test failed.");
        }
        String finalResult = result;
        main.post(() -> status.accept(finalResult));
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int value) { return ui.dp(value); }
    private String message(Exception error, String fallback) { return error.getMessage() == null ? fallback : error.getMessage(); }
}
