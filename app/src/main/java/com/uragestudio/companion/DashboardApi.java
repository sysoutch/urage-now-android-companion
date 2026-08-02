package com.uragestudio.companion;

import android.content.ContentResolver;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Consumer;

public final class DashboardApi {
    public record Pairing(String deviceId, String token) {}
    public record DashboardInfo(String name, int protocol, boolean secure) {}
    public record DashboardTheme(String theme, String updatedAt) {}
    public record MediaPage(List<MediaItem> items, int total, String nextCursor) {}
    public record UploadSession(String id, long offset, long totalSize) {}
    public record ChatMessage(String role, String content) {}
    public record WorkflowItem(String id, String kind, String fileName, String title, String downloadUrl, String thumbnailUrl) {}
    public record ImageWorkflowOptions(
        String prompt, String negativePrompt, int width, int height,
        Long seed, Integer steps, Double cfg, boolean autoPrompt,
        String imageId, String imageFileName,
        String matrixSourceId, String matrixSourceFileName
    ) {}
    private final String baseUrl;
    private final String token;
    private final String certificateSha256;

    public DashboardApi(String baseUrl, String token, String certificateSha256) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.certificateSha256 = certificateSha256 == null ? "" : certificateSha256;
    }

    public String absoluteUrl(String path) {
        return path.startsWith("http") ? path : baseUrl + path;
    }

    public String token() {
        return token;
    }

    public Pairing pair(String code, String deviceName) throws Exception {
        JSONObject body = new JSONObject().put("code", code).put("deviceName", deviceName);
        JSONObject response = requestJson("POST", "/api/companion/pair", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", false);
        return new Pairing(response.getString("deviceId"), response.getString("token"));
    }

    public Pairing pairToken(String temporaryToken, String deviceName) throws Exception {
        JSONObject body = new JSONObject().put("token", temporaryToken).put("deviceName", deviceName);
        JSONObject response = requestJson("POST", "/api/companion/pair", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", false);
        return new Pairing(response.getString("deviceId"), response.getString("token"));
    }

    public DashboardInfo getInfo() throws Exception {
        JSONObject response = requestJson("GET", "/api/companion/info", null, null, false);
        return new DashboardInfo(
            response.optString("name", "URage NOW"),
            response.optInt("protocol", 0),
            response.optBoolean("secure", false)
        );
    }

    public DashboardTheme getTheme() throws Exception {
        JSONObject response = requestJson("GET", "/api/companion/theme", null, null, true);
        return new DashboardTheme(response.optString("theme", "fire"), response.optString("updatedAt"));
    }

    public MediaPage listMedia(String kind, String cursor, int limit) throws Exception {
        String path = "/api/companion/media?kind=" + kind + "&limit=" + limit;
        if (cursor != null && !cursor.isEmpty()) path += "&cursor=" + Uri.encode(cursor);
        JSONObject response = requestJson("GET", path, null, null, true);
        JSONArray items = response.getJSONArray("items");
        List<MediaItem> result = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            result.add(new MediaItem(
                item.optString("id"), item.optString("kind", kind), item.optString("fileName"),
                item.optString("title"), item.optString("createdAt"), item.optString("downloadUrl"),
                item.optString("thumbnailUrl"), item.optString("source"), item.optLong("size", -1)
            ));
        }
        return new MediaPage(result, response.optInt("total", result.size()), response.isNull("nextCursor") ? null : response.optString("nextCursor", null));
    }

    public void updateMediaTitle(MediaItem item, String title) throws Exception {
        String path = "/api/companion/media/metadata?kind=" + Uri.encode(item.kind())
            + "&id=" + Uri.encode(item.id()) + "&file=" + Uri.encode(item.fileName()) + "&source=" + Uri.encode(item.source());
        JSONObject body = new JSONObject().put("title", title);
        requestJson("PATCH", path, body.toString().getBytes(StandardCharsets.UTF_8), "application/json", true);
    }

    public void deleteMedia(MediaItem item) throws Exception {
        String path = "/api/companion/media?kind=" + Uri.encode(item.kind())
            + "&id=" + Uri.encode(item.id()) + "&file=" + Uri.encode(item.fileName()) + "&source=" + Uri.encode(item.source());
        requestJson("DELETE", path, null, null, true);
    }

    public MediaItem upload(ContentResolver resolver, Uri uri, String kind, String fileName, String contentType) throws Exception {
        HttpURLConnection connection = open("POST", "/api/companion/media?kind=" + kind, true);
        connection.setRequestProperty("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        connection.setRequestProperty("X-File-Name", Uri.encode(fileName));
        connection.setDoOutput(true);
        try (InputStream input = resolver.openInputStream(uri); OutputStream output = connection.getOutputStream()) {
            if (input == null) throw new IOException("The selected file could not be opened.");
            copy(input, output);
        }
        JSONObject item = readJsonResponse(connection);
        return new MediaItem(item.optString("id"), kind, item.optString("fileName"), item.optString("title"), item.optString("createdAt"), item.optString("downloadUrl"), item.optString("thumbnailUrl"), item.optString("source", "upload"), item.optLong("size", -1));
    }

    public UploadSession createUpload(String kind, String fileName, String contentType, long totalSize) throws Exception {
        JSONObject body = new JSONObject()
            .put("kind", kind).put("fileName", fileName).put("contentType", contentType).put("totalSize", totalSize);
        return uploadSession(requestJson("POST", "/api/companion/uploads", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", true));
    }

    public UploadSession uploadStatus(String uploadId) throws Exception {
        return uploadSession(requestJson("GET", "/api/companion/uploads/status?uploadId=" + Uri.encode(uploadId), null, null, true));
    }

    public UploadSession uploadChunk(String uploadId, long offset, byte[] bytes, int length) throws Exception {
        HttpURLConnection connection = open("POST", "/api/companion/uploads/chunk?uploadId=" + Uri.encode(uploadId), true);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("X-Upload-Offset", Long.toString(offset));
        connection.setFixedLengthStreamingMode(length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes, 0, length);
        }
        return uploadSession(readJsonResponse(connection));
    }

    public void completeUpload(String uploadId) throws Exception {
        requestJson("POST", "/api/companion/uploads/complete?uploadId=" + Uri.encode(uploadId), new byte[0], "application/json", true);
    }

    public String chat(List<ChatMessage> history, String prompt) throws Exception {
        JSONArray transcript = new JSONArray();
        for (ChatMessage message : history) {
            transcript.put(new JSONObject().put("role", message.role()).put("content", message.content()));
        }
        JSONObject body = new JSONObject().put("prompt", prompt).put("history", transcript);
        return requestWorkflowJson("/api/companion/workflows/chat", body).getString("response");
    }

    public String chatStream(List<ChatMessage> history, String prompt, Consumer<String> onDelta) throws Exception {
        JSONArray transcript = new JSONArray();
        for (ChatMessage message : history) {
            transcript.put(new JSONObject().put("role", message.role()).put("content", message.content()));
        }
        JSONObject body = new JSONObject().put("prompt", prompt).put("history", transcript);
        HttpURLConnection connection = open("POST", "/api/companion/workflows/chat-stream", true);
        connection.setReadTimeout(15 * 60 * 1000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException(readError(connection, status));
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                JSONObject event = new JSONObject(line.substring(6));
                if ("response-delta".equals(event.optString("type"))) {
                    String delta = event.optString("delta");
                    response.append(delta);
                    onDelta.accept(delta);
                } else if ("done".equals(event.optString("type")) && response.length() == 0) {
                    response.append(event.optString("response"));
                } else if ("error".equals(event.optString("type"))) {
                    throw new IOException(event.optString("message", "Chat stream failed."));
                }
            }
        }
        return response.toString();
    }

    public WorkflowItem generateImage(ImageWorkflowOptions options) throws Exception {
        JSONObject body = new JSONObject()
            .put("prompt", options.prompt()).put("negativePrompt", options.negativePrompt())
            .put("width", options.width()).put("height", options.height()).put("autoPrompt", options.autoPrompt());
        if (options.seed() != null) body.put("seed", options.seed());
        if (options.steps() != null) body.put("steps", options.steps());
        if (options.cfg() != null) body.put("cfg", options.cfg());
        if (options.imageId() != null && !options.imageId().isBlank()) body.put("imageId", options.imageId());
        if (options.imageFileName() != null && !options.imageFileName().isBlank()) body.put("imageFileName", options.imageFileName());
        return workflowItem(requestWorkflowJson("/api/companion/workflows/image", body).getJSONObject("item"));
    }

    public String interpretImages(List<MediaItem> images, String mode, String prompt) throws Exception {
        JSONArray sources = new JSONArray();
        for (MediaItem image : images) {
            sources.put(new JSONObject().put("id", image.id()).put("fileName", image.fileName()));
        }
        JSONObject body = new JSONObject()
            .put("images", sources).put("mode", mode).put("prompt", prompt);
        return requestWorkflowJson("/api/companion/workflows/image/interpret", body).getString("prompt");
    }

    public String improveImagePrompt(String prompt, String negativePrompt, String instructions) throws Exception {
        JSONObject body = new JSONObject()
            .put("prompt", prompt).put("negativePrompt", negativePrompt).put("instructions", instructions);
        return requestWorkflowJson("/api/companion/workflows/image/improve-prompt", body).getString("prompt");
    }

    public WorkflowItem generateAudio(String prompt, int seconds) throws Exception {
        JSONObject body = new JSONObject().put("prompt", prompt).put("seconds", seconds);
        return workflowItem(requestWorkflowJson("/api/companion/workflows/audio", body).getJSONObject("item"));
    }

    public WorkflowItem generateMusic(String tags, String lyrics, int seconds) throws Exception {
        JSONObject body = new JSONObject().put("tags", tags).put("lyrics", lyrics).put("seconds", seconds);
        return workflowItem(requestWorkflowJson("/api/companion/workflows/music", body).getJSONObject("item"));
    }

    public WorkflowItem generateVideo(String prompt, String negativePrompt, int seconds, int fps,
                                      int width, int height, Integer steps, Long seed,
                                      String imageId, String imageFileName) throws Exception {
        JSONObject body = new JSONObject()
            .put("prompt", prompt).put("negativePrompt", negativePrompt)
            .put("seconds", seconds).put("fps", fps).put("width", width).put("height", height);
        if (steps != null) body.put("steps", steps);
        if (seed != null) body.put("seed", seed);
        if (imageId != null && !imageId.isEmpty()) body.put("imageId", imageId);
        if (imageFileName != null && !imageFileName.isEmpty()) body.put("imageFileName", imageFileName);
        return workflowItem(requestWorkflowJson("/api/companion/workflows/video", body).getJSONObject("item"));
    }

    public WorkflowItem generateModel3d(
        String sourceMode, String prompt, String imageId, String imageFileName, boolean generateLowPoly
    ) throws Exception {
        JSONObject body = new JSONObject()
            .put("sourceMode", sourceMode)
            .put("prompt", prompt)
            .put("generateLowPoly", generateLowPoly);
        if (imageId != null && !imageId.isEmpty()) body.put("imageId", imageId);
        if (imageFileName != null && !imageFileName.isEmpty()) body.put("imageFileName", imageFileName);
        return workflowItem(requestWorkflowJson("/api/companion/workflows/model3d", body).getJSONObject("item"));
    }

    /** Requests the paired dashboard host to open one of its generated models in Bambu Studio. */
    public void openModelInBambuStudio(MediaItem model) throws Exception {
        if (model == null || !"model3d".equals(model.kind()) || model.id() == null || model.id().isBlank()
            || model.fileName() == null || model.fileName().isBlank()) {
            throw new IllegalArgumentException("Choose a generated 3D model first.");
        }
        JSONObject body = new JSONObject()
            .put("applicationId", "bambu-studio")
            .put("modelId", model.id())
            .put("fileName", model.fileName());
        requestJson("POST", "/api/companion/model3d/print-applications/launch",
            body.toString().getBytes(StandardCharsets.UTF_8), "application/json", true);
    }

    private WorkflowItem workflowItem(JSONObject item) {
        return new WorkflowItem(
            item.optString("id"), item.optString("kind"), item.optString("fileName"),
            item.optString("title"), item.optString("downloadUrl"), item.optString("thumbnailUrl")
        );
    }

    private JSONObject requestWorkflowJson(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open("POST", path, true);
        connection.setReadTimeout(15 * 60 * 1000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readJsonResponse(connection);
    }

    private UploadSession uploadSession(JSONObject value) {
        return new UploadSession(value.optString("id"), value.optLong("offset"), value.optLong("totalSize"));
    }

    public byte[] downloadBytes(String path) throws Exception {
        HttpURLConnection connection = open("GET", path, true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException(readError(connection, status));
        try (InputStream input = connection.getInputStream()) {
            return readAll(input);
        }
    }

    public String download(MediaItem item, OutputStream output) throws Exception {
        HttpURLConnection connection = open("GET", item.downloadUrl(), true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException(readError(connection, status));
        try (InputStream input = connection.getInputStream()) {
            copy(input, output);
        }
        return connection.getContentType();
    }

    public HttpURLConnection openDownload(String path, long offset) throws Exception {
        HttpURLConnection connection = open("GET", path, true);
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        return connection;
    }

    private JSONObject requestJson(String method, String path, byte[] body, String contentType, boolean authenticated) throws Exception {
        HttpURLConnection connection = open(method, path, authenticated);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        return readJsonResponse(connection);
    }

    private HttpURLConnection open(String method, String path, boolean authenticated) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(absoluteUrl(path)).openConnection();
        if (connection instanceof HttpsURLConnection && !certificateSha256.trim().isEmpty()) {
            try {
                ((HttpsURLConnection) connection).setSSLSocketFactory(CertificatePinning.socketFactory(certificateSha256));
            } catch (Exception error) {
                throw new IOException("Could not configure dashboard certificate trust.", error);
            }
        }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(30_000);
        if (authenticated && !token.trim().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static JSONObject readJsonResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException(readError(connection, status));
        try (InputStream input = connection.getInputStream()) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
    }

    private static String readError(HttpURLConnection connection, int status) throws IOException {
        InputStream error = connection.getErrorStream();
        if (error == null) return "Dashboard request failed with HTTP " + status + ".";
        String body = new String(readAll(error), StandardCharsets.UTF_8);
        try {
            return new JSONObject(body).optString("error", body);
        } catch (Exception ignored) {
            return body;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
    }
}
