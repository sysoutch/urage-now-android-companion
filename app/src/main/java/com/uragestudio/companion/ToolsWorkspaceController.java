package com.uragestudio.companion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Live, permission-checked browser for the paired dashboard's static tool catalog. */
final class ToolsWorkspaceController {
    private static final int FILE_CHOOSER_REQUEST_CODE = 4621;
    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final Supplier<DashboardApi> apiSupplier;
    private final Consumer<String> appStatus;
    private final Consumer<Exception> reportError;
    private final MobileUiKit ui;
    private final LinearLayout view;
    private final LinearLayout categoryTabs;
    private final LinearLayout toolList;
    private final ScrollView toolListScroll;
    private final WebView webView;
    private final TextView status;
    private final TextView activeToolTitle;
    private final Button backToTools;
    private final Button openInBrowser;
    private List<DashboardApi.ToolItem> tools = List.of();
    private DashboardApi activeApi;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean loaded;

    ToolsWorkspaceController(
        Activity activity, ExecutorService executor, Handler main, Supplier<DashboardApi> apiSupplier,
        Consumer<String> appStatus, Consumer<Exception> reportError
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.apiSupplier = apiSupplier;
        this.appStatus = appStatus;
        this.reportError = reportError;
        ui = new MobileUiKit(activity);
        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(ui.backgroundColor());

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(6));
        header.addView(ui.screenTitle("Tools"));
        activeToolTitle = ui.body("Live tools from the paired dashboard");
        header.addView(activeToolTitle, ui.spacedMatchWrap());
        status = ui.status("Open Tools to load the server catalog.");
        header.addView(status, ui.spacedMatchWrap());
        backToTools = ui.button("Back to tools", MobileUiKit.ActionStyle.QUIET);
        backToTools.setVisibility(View.GONE);
        backToTools.setOnClickListener(ignored -> showToolList());
        header.addView(backToTools, ui.matchWrap());
        openInBrowser = ui.button("Open tool in browser", MobileUiKit.ActionStyle.QUIET);
        openInBrowser.setVisibility(View.GONE);
        openInBrowser.setOnClickListener(ignored -> openActiveToolInBrowser());
        header.addView(openInBrowser, ui.matchWrap());
        view.addView(header, ui.matchWrap());

        categoryTabs = new LinearLayout(activity);
        categoryTabs.setOrientation(LinearLayout.HORIZONTAL);
        categoryTabs.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(6));
        HorizontalScrollView categories = new HorizontalScrollView(activity);
        categories.setHorizontalScrollBarEnabled(false);
        categories.addView(categoryTabs);
        view.addView(categories, ui.matchWrap());

        toolList = new LinearLayout(activity);
        toolList.setOrientation(LinearLayout.VERTICAL);
        toolList.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(16));
        toolListScroll = new ScrollView(activity);
        toolListScroll.setFillViewport(true);
        toolListScroll.addView(toolList);
        view.addView(toolListScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        webView = createWebView();
        webView.setVisibility(View.GONE);
        view.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        view.setVisibility(View.GONE);
    }

    View view() { return view; }

    void show(boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && !loaded) refresh();
    }

    void close() {
        webView.stopLoading();
        cancelPendingFileChooser();
        webView.destroy();
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) return false;
        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        if (callback != null) callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        return true;
    }

    private void refresh() {
        status.setText("Loading tools from the paired dashboard...");
        executor.execute(() -> {
            try {
                DashboardApi api = apiSupplier.get();
                if (api == null) throw new IllegalStateException("Pair with a dashboard before opening Tools.");
                List<DashboardApi.ToolItem> result = api.listTools();
                main.post(() -> {
                    activeApi = api;
                    tools = result;
                    loaded = true;
                    renderCategories();
                    status.setText(result.isEmpty() ? "The dashboard has no browser tools." : result.size() + " tools available.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    status.setText(error.getMessage() == null ? "Could not load tools." : error.getMessage());
                    reportError.accept(error);
                });
            }
        });
    }

    private void renderCategories() {
        categoryTabs.removeAllViews();
        Map<String, String> categories = new LinkedHashMap<>();
        for (DashboardApi.ToolItem tool : tools) categories.putIfAbsent(tool.category(), tool.categoryLabel());
        for (Map.Entry<String, String> category : categories.entrySet()) {
            MaterialButton tab = ui.button(category.getValue(), MobileUiKit.ActionStyle.QUIET);
            tab.setMinWidth(0);
            tab.setOnClickListener(ignored -> showCategory(category.getKey(), category.getValue()));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            layout.setMargins(ui.dp(3), 0, ui.dp(3), 0);
            categoryTabs.addView(tab, layout);
        }
        if (!categories.isEmpty()) {
            Map.Entry<String, String> first = categories.entrySet().iterator().next();
            showCategory(first.getKey(), first.getValue());
        } else {
            toolList.removeAllViews();
            toolList.addView(ui.body("No server tools are available."));
        }
    }

    private void showCategory(String category, String label) {
        showToolList();
        toolList.removeAllViews();
        toolList.addView(ui.overline(label), ui.spacedMatchWrap());
        for (DashboardApi.ToolItem tool : tools) {
            if (!category.equals(tool.category())) continue;
            LinearLayout content = ui.cardContent();
            FrameLayout cover = createToolCover(tool);
            content.addView(ui.sectionTitle(tool.title()));
            content.addView(ui.body(tool.description()));
            Button open = ui.button("Open tool", MobileUiKit.ActionStyle.SECONDARY);
            open.setOnClickListener(ignored -> openTool(tool));
            content.addView(open, ui.spacedMatchWrap());
            com.google.android.material.card.MaterialCardView card = ui.card();
            LinearLayout cardLayout = new LinearLayout(activity);
            cardLayout.setOrientation(LinearLayout.VERTICAL);
            cardLayout.addView(cover, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(132)));
            cardLayout.addView(content, ui.matchWrap());
            card.addView(cardLayout);
            toolList.addView(card, ui.spacedMatchWrap());
        }
    }

    private FrameLayout createToolCover(DashboardApi.ToolItem tool) {
        FrameLayout surface = new FrameLayout(activity);
        surface.setBackground(ui.selectedControlBackground());
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(tool.title() + " cover image");
        surface.addView(image, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView fallback = ui.overline(tool.categoryLabel() + " / " + tool.title());
        fallback.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        surface.addView(fallback, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (!tool.coverPath().isBlank()) loadToolCover(tool.coverPath(), image, fallback);
        return surface;
    }

    private void loadToolCover(String coverPath, ImageView image, TextView fallback) {
        DashboardApi api = activeApi;
        if (api == null) return;
        executor.execute(() -> {
            try {
                DashboardApi.ToolResource resource = api.readToolResource(coverPath);
                byte[] data = resource.data();
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                main.post(() -> {
                    if (image.getParent() == null) return;
                    image.setImageBitmap(bitmap);
                    fallback.setVisibility(View.GONE);
                });
            } catch (Exception ignored) {
                // A decorative cover must never prevent its tool from being opened.
            }
        });
    }

    private void openTool(DashboardApi.ToolItem tool) {
        DashboardApi api = activeApi;
        if (api == null) return;
        status.setText("Opening " + tool.title() + "...");
        executor.execute(() -> {
            try {
                DashboardApi.ToolResource resource = api.readToolResource(tool.entryPath());
                String html = new String(resource.data(), StandardCharsets.UTF_8);
                main.post(() -> {
                    activeToolTitle.setText(tool.title());
                    backToTools.setVisibility(View.VISIBLE);
                    openInBrowser.setVisibility(View.VISIBLE);
                    openInBrowser.setTag(tool.entryPath());
                    toolListScroll.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                    status.setText(tool.categoryLabel() + " · served by dashboard");
                    webView.loadDataWithBaseURL(api.absoluteUrl(tool.entryPath()), html, "text/html", "UTF-8", null);
                    appStatus.accept("Opened " + tool.title() + " from the dashboard.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    status.setText(error.getMessage() == null ? "Could not open tool." : error.getMessage());
                    reportError.accept(error);
                });
            }
        });
    }

    private void showToolList() {
        webView.stopLoading();
        webView.setVisibility(View.GONE);
        toolListScroll.setVisibility(View.VISIBLE);
        backToTools.setVisibility(View.GONE);
        openInBrowser.setVisibility(View.GONE);
        openInBrowser.setTag(null);
        activeToolTitle.setText("Live tools from the paired dashboard");
    }

    private void openActiveToolInBrowser() {
        DashboardApi api = activeApi;
        Object entryPath = openInBrowser.getTag();
        if (api == null || !(entryPath instanceof String) || ((String) entryPath).isBlank()) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(api.absoluteUrl((String) entryPath))));
        } catch (Exception error) {
            status.setText("No browser is available to open this tool.");
            reportError.accept(error);
        }
    }

    private WebView createWebView() {
        WebView browser = new WebView(activity);
        browser.setBackgroundColor(ui.backgroundColor());
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                WebView view, ValueCallback<Uri[]> callback, FileChooserParams parameters
            ) {
                cancelPendingFileChooser();
                fileChooserCallback = callback;
                try {
                    Intent filePicker = parameters.createIntent();
                    filePicker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivityForResult(filePicker, FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception error) {
                    cancelPendingFileChooser();
                    status.setText("Android could not open a file picker for this tool.");
                    reportError.accept(error);
                    return false;
                }
            }
        });
        browser.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                DashboardApi api = activeApi;
                String resourcePath = uri == null ? null : uri.getEncodedPath();
                if (api == null || !isToolResourcePath(resourcePath)) return null;
                try {
                    DashboardApi.ToolResource resource = api.readToolResource(resourcePath);
                    String contentType = resource.contentType() == null ? "application/octet-stream" : resource.contentType();
                    String[] contentParts = contentType.split(";", 2);
                    String encoding = contentParts.length > 1 && contentParts[1].contains("charset=")
                        ? contentParts[1].replace("charset=", "").trim()
                        : null;
                    return new WebResourceResponse(contentParts[0], encoding, new ByteArrayInputStream(resource.data()));
                } catch (Exception error) {
                    main.post(() -> status.setText(error.getMessage() == null ? "A tool resource failed to load." : error.getMessage()));
                    return new WebResourceResponse("text/plain", "UTF-8", 502, "Tool resource failed", Map.of(),
                        new ByteArrayInputStream("Tool resource failed to load.".getBytes(StandardCharsets.UTF_8)));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && isToolResourcePath(uri.getEncodedPath())) return false;
                main.post(() -> status.setText("External navigation is blocked inside server tools."));
                return true;
            }
        });
        return browser;
    }

    private static boolean isToolResourcePath(String path) {
        return path != null && path.startsWith("/tools/");
    }

    private void cancelPendingFileChooser() {
        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        if (callback != null) callback.onReceiveValue(null);
    }
}
