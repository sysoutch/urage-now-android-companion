package com.uragestudio.companion;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns Chat UI, streaming, Matrix synchronization, and local conversation persistence. */
final class ChatWorkspaceController {
    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final Supplier<DashboardApi> dashboardApi;
    private final Supplier<MatrixSdkRelayClient> matrixRelay;
    private final Supplier<String> route;
    private final Consumer<String> status;
    private final SharedPreferences preferences;
    private final MobileUiKit ui;
    private final List<DashboardApi.ChatMessage> history = new ArrayList<>();
    private final ScrollView view;
    private ChatBubbleTranscript transcript;

    ChatWorkspaceController(
        Activity activity, ExecutorService executor, Handler main,
        Supplier<DashboardApi> dashboardApi, Supplier<MatrixSdkRelayClient> matrixRelay,
        Supplier<String> route, Consumer<String> status
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.dashboardApi = dashboardApi;
        this.matrixRelay = matrixRelay;
        this.route = route;
        this.status = status;
        ui = new MobileUiKit(activity);
        preferences = activity.getSharedPreferences("workflow_workspace", Activity.MODE_PRIVATE);
        loadHistory();
        view = new ScrollView(activity);
        view.addView(build());
        view.setVisibility(View.GONE);
    }

    View view() {
        return view;
    }

    void show(boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private LinearLayout build() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(24));
        panel.addView(ui.screenTitle("Chat Studio"));
        panel.addView(ui.body("Continue a compact conversation with the dashboard's active text model."));
        panel.addView(ui.status("Uses the connection selected under Connect for this workflow."), matchWrap());
        transcript = new ChatBubbleTranscript(activity);
        panel.addView(transcript.view(), matchWrap());

        EditText prompt = ui.input("Message LazyDev...");
        prompt.setMinLines(3);
        prompt.setGravity(Gravity.TOP);
        panel.addView(ui.field("Message", "Replies stream into the conversation as they arrive.", prompt), matchWrap());
        TextView preview = text("Markdown preview appears as you type.", 13, ui.textMutedColor());
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        preview.setBackground(ui.controlBackground());
        panel.addView(ui.overline("Live Markdown preview"), matchWrap());
        panel.addView(preview, matchWrap());
        prompt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                String markdown = value.toString();
                preview.setText(markdown.isBlank()
                    ? "Markdown preview appears as you type."
                    : MarkdownRichText.render(activity, markdown));
            }
            public void afterTextChanged(Editable value) {}
        });

        Button send = ui.button("Send message", MobileUiKit.ActionStyle.PRIMARY);
        send.setOnClickListener(ignored -> send(prompt, send));
        panel.addView(send, matchWrap());
        Button synchronize = ui.button("Sync Matrix conversation", MobileUiKit.ActionStyle.SECONDARY);
        synchronize.setOnClickListener(ignored -> synchronize(synchronize));
        panel.addView(synchronize, matchWrap());
        Button clear = ui.button("Clear local conversation", MobileUiKit.ActionStyle.DANGER);
        clear.setOnClickListener(ignored -> {
            history.clear();
            saveHistory();
            renderTranscript();
        });
        panel.addView(clear, matchWrap());
        renderTranscript();
        return panel;
    }

    private void send(EditText prompt, Button send) {
        String value = prompt.getText().toString().trim();
        if (value.isEmpty()) {
            status.accept("Enter a Chat Studio message first.");
            return;
        }
        boolean useMatrix = usesMatrix();
        DashboardApi api = useMatrix ? null : dashboardApi.get();
        if (!useMatrix && api == null) return;
        send.setEnabled(false);
        status.accept("Waiting for Chat Studio…");
        List<DashboardApi.ChatMessage> context = new ArrayList<>(history);
        executor.execute(() -> {
            try {
                StringBuilder partial = new StringBuilder();
                Consumer<String> stream = delta -> {
                    partial.append(delta);
                    main.post(() -> {
                        transcript.render(history, value, partial.toString());
                        scrollToConversationEnd();
                    });
                };
                String reply = useMatrix
                    ? requiredMatrixRelay().chatStream(value, stream)
                    : api.chatStream(context, value, stream);
                history.add(new DashboardApi.ChatMessage("user", value));
                history.add(new DashboardApi.ChatMessage("assistant", reply));
                while (history.size() > 20) history.remove(0);
                saveHistory();
                main.post(() -> {
                    prompt.setText("");
                    renderTranscript();
                    send.setEnabled(true);
                    status.accept("Chat reply received.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    send.setEnabled(true);
                    status.accept(message(error, "Chat request failed."));
                });
            }
        });
    }

    private void synchronize(Button button) {
        button.setEnabled(false);
        status.accept("Synchronizing the encrypted Matrix conversation…");
        executor.execute(() -> {
            try {
                List<DashboardApi.ChatMessage> synchronizedHistory = requiredMatrixRelay().synchronizedChatHistory(20);
                main.post(() -> {
                    history.clear();
                    history.addAll(synchronizedHistory);
                    saveHistory();
                    renderTranscript();
                    button.setEnabled(true);
                    status.accept("Matrix conversation synchronized.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    button.setEnabled(true);
                    status.accept(message(error, "Matrix synchronization failed."));
                });
            }
        });
    }

    private void renderTranscript() {
        transcript.render(history);
        scrollToConversationEnd();
    }

    private void scrollToConversationEnd() {
        view.post(() -> view.fullScroll(View.FOCUS_DOWN));
    }

    private MatrixSdkRelayClient requiredMatrixRelay() {
        MatrixSdkRelayClient relay = matrixRelay.get();
        if (relay == null) throw new IllegalStateException("Configure the Matrix Internet Relay under Connection first.");
        return relay;
    }

    private boolean usesMatrix() {
        return ConnectionRouteStore.MATRIX.equals(route.get());
    }

    private void loadHistory() {
        try {
            JSONArray entries = new JSONArray(preferences.getString("chatHistory", "[]"));
            for (int index = Math.max(0, entries.length() - 20); index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry != null) history.add(new DashboardApi.ChatMessage(entry.optString("role"), entry.optString("content")));
            }
        } catch (Exception ignored) {
            history.clear();
        }
    }

    private void saveHistory() {
        JSONArray entries = new JSONArray();
        for (DashboardApi.ChatMessage message : history) {
            try {
                entries.put(new JSONObject().put("role", message.role()).put("content", message.content()));
            } catch (Exception ignored) {}
        }
        preferences.edit().putString("chatHistory", entries.toString()).apply();
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.15f);
        return text;
    }

    private String message(Exception error, String fallback) {
        return error.getMessage() == null ? fallback : error.getMessage();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return ui.dp(value);
    }
}
