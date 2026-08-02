package com.uragestudio.companion;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.MotionEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Sandboxed local Three.js viewport. Renderer dependencies and the current
 * model are exposed only through an intercepted private in-app origin.
 */
final class Model3dPreviewView extends WebView {
    private static final String ORIGIN = "https://urage-model-viewer.local";
    private static final String ASSET_ROOT = "model-viewer/";
    private final Activity activity;
    private File modelFile;
    private boolean pageReady;

    @SuppressLint("SetJavaScriptEnabled")
    Model3dPreviewView(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Color.rgb(11, 17, 27));
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        setWebViewClient(new LocalViewerClient());
        setOnTouchListener((view, event) -> {
            if (view.getParent() != null) {
                boolean interacting = event.getActionMasked() != MotionEvent.ACTION_UP
                    && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
                view.getParent().requestDisallowInterceptTouchEvent(interacting);
            }
            return false;
        });
        loadUrl(ORIGIN + "/index.html");
    }

    void showModel(File file) {
        modelFile = file;
        String format = previewFormat(file);
        if (format == null) {
            evaluateWhenReady("window.showError(" + JSONObject.quote(
                "Preview currently supports GLB and FBX models. Download " + file.getName()
                    + " to open this format in a desktop 3D tool.") + ");");
            return;
        }
        evaluateWhenReady("window.loadModel(" + JSONObject.quote(format) + ");");
    }

    static boolean supportsPreview(File file) {
        return previewFormat(file) != null;
    }

    private static String previewFormat(File file) {
        if (file == null) return null;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".glb")) return "glb";
        if (name.endsWith(".fbx")) return "fbx";
        return null;
    }

    private void evaluateWhenReady(String script) {
        if (pageReady) evaluateJavascript(script, null);
    }

    private WebResourceResponse response(String mimeType, InputStream stream) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", ORIGIN);
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream);
    }

    private WebResourceResponse missing() {
        return new WebResourceResponse(
            "text/plain", "UTF-8", 404, "Not Found", new HashMap<>(),
            new ByteArrayInputStream("Not found".getBytes(StandardCharsets.UTF_8))
        );
    }

    private final class LocalViewerClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(
            WebView view, WebResourceRequest request
        ) {
            Uri url = request.getUrl();
            if (!ORIGIN.equals(url.getScheme() + "://" + url.getHost())) return missing();
            String path = url.getPath();
            try {
                if ("/model.glb".equals(path) || "/model.fbx".equals(path)) {
                    File current = modelFile;
                    boolean wantsFbx = "/model.fbx".equals(path);
                    boolean validFormat = current != null && (wantsFbx
                        ? current.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".fbx")
                        : current.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".glb"));
                    return validFormat && current.isFile()
                        ? response(wantsFbx ? "application/octet-stream" : "model/gltf-binary", new FileInputStream(current))
                        : missing();
                }
                String asset = switch (path) {
                    case "/index.html" -> "index.html";
                    case "/assets/three.module.min.js" -> "three.module.min.js";
                    case "/assets/loaders/GLTFLoader.js" -> "loaders/GLTFLoader.js";
                    case "/assets/loaders/FBXLoader.js" -> "loaders/FBXLoader.js";
                    case "/assets/controls/OrbitControls.js" -> "controls/OrbitControls.js";
                    case "/assets/utils/BufferGeometryUtils.js" -> "utils/BufferGeometryUtils.js";
                    case "/assets/libs/fflate.module.js" -> "libs/fflate.module.js";
                    case "/assets/curves/NURBSCurve.js" -> "curves/NURBSCurve.js";
                    case "/assets/curves/NURBSUtils.js" -> "curves/NURBSUtils.js";
                    default -> "";
                };
                if (asset.isBlank()) return missing();
                String mime = asset.endsWith(".html") ? "text/html" : "text/javascript";
                return response(mime, activity.getAssets().open(ASSET_ROOT + asset));
            } catch (IOException ignored) {
                return missing();
            }
        }

        @Override public boolean shouldOverrideUrlLoading(
            WebView view, WebResourceRequest request
        ) {
            Uri url = request.getUrl();
            return !ORIGIN.equals(url.getScheme() + "://" + url.getHost());
        }

        @Override public void onPageFinished(WebView view, String url) {
            pageReady = true;
            if (modelFile != null) showModel(modelFile);
        }
    }
}
