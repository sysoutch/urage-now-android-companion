package com.uragestudio.companion;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns Chat UI, streaming, Matrix synchronization, and local conversation persistence. */
final class ChatWorkspaceController {
    private static final int REQUEST_AUDIO_FILE = 1001;
    private static final int RECORDING_PERMISSION_REQUEST = 1002;
    private static final String AUTO_SEND_VOICE_TRANSCRIPTS = "chatAutoSendVoiceTranscripts";
    private static final String AUTO_SEND_RECORDING_ON_STOP = "chatAutoSendRecordingOnStop";
    private static final String AUTO_REPLY_TTS = "chatAutoReplyTts";
    private static final String TTS_MODE = "chatTtsMode";

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
    private final LinearLayout view;
    private ChatBubbleTranscript transcript;
    private ScrollView transcriptScroll;

    // Voice recording state
    private MediaRecorder mediaRecorder;
    private File currentRecordingFile;
    private boolean isRecording = false;
    private TextView recordingIndicator;
    private Button micButton;
    private Button sendButton;
    private EditText promptEditText;
    private File pendingAudioAttachment;
    private TextView audioAttachmentLabel;
    private final ChatSpeechPlayer speechPlayer;

    private record AudioMessageRequest(
        int historyIndex,
        List<DashboardApi.ChatMessage> context
    ) {}

    ChatWorkspaceController(
        Activity activity,
        ExecutorService executor,
        Handler main,
        Supplier<DashboardApi> dashboardApi,
        Supplier<MatrixSdkRelayClient> matrixRelay,
        Supplier<String> route,
        Consumer<String> status
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.dashboardApi = dashboardApi;
        this.matrixRelay = matrixRelay;
        this.route = route;
        this.status = status;
        ui = new MobileUiKit(activity);
        preferences = activity.getSharedPreferences(
            "workflow_workspace",
            Activity.MODE_PRIVATE
        );
        speechPlayer = new ChatSpeechPlayer(activity);
        loadHistory();
        view = build();
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

        transcript = new ChatBubbleTranscript(activity);
        transcriptScroll = new ScrollView(activity);
        transcriptScroll.setFillViewport(true);
        transcriptScroll.addView(transcript.view());
        panel.addView(transcriptScroll, weightedHeight());

        recordingIndicator = new TextView(activity);
        recordingIndicator.setTextColor(
            activity.getResources().getColor(android.R.color.holo_red_dark)
        );
        recordingIndicator.setTextSize(12f);
        recordingIndicator.setVisibility(View.GONE);
        recordingIndicator.setText("● Recording...");

        LinearLayout composerDock = new LinearLayout(activity);
        composerDock.setOrientation(LinearLayout.VERTICAL);
        composerDock.setPadding(0, dp(8), 0, 0);

        // =========================================================
        // Top row: message field + three action buttons
        // =========================================================

        LinearLayout composerTopRow = new LinearLayout(activity);
        composerTopRow.setOrientation(LinearLayout.HORIZONTAL);
        composerTopRow.setGravity(Gravity.TOP);
        composerTopRow.setBackground(ui.controlBackground());

        // ---------------------------------------------------------
        // Message area
        // ---------------------------------------------------------

        LinearLayout messageArea = new LinearLayout(activity);
        messageArea.setOrientation(LinearLayout.VERTICAL);

        promptEditText = ui.input("Message LazyDev...");
        promptEditText.setId(android.view.View.generateViewId());
        promptEditText.setMinLines(4);
        promptEditText.setGravity(Gravity.TOP);
        promptEditText.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        promptEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateComposerPrimaryAction();
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        audioAttachmentLabel = new TextView(activity);
        audioAttachmentLabel.setTextSize(13f);
        audioAttachmentLabel.setTextColor(
            activity.getResources().getColor(
                android.R.color.holo_green_dark
            )
        );
        audioAttachmentLabel.setVisibility(View.GONE);
        audioAttachmentLabel.setPadding(
            0,
            dp(4),
            0,
            dp(4)
        );

        messageArea.addView(
            audioAttachmentLabel,
            matchWrap()
        );

        messageArea.addView(
            recordingIndicator,
            matchWrap()
        );

        messageArea.addView(
            ui.overline("Message"),
            matchWrap()
        );

        messageArea.addView(
            promptEditText,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            )
        );

        composerTopRow.addView(
            messageArea,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
            )
        );

        // ---------------------------------------------------------
        // Three buttons SIDE BY SIDE
        // ---------------------------------------------------------

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.TOP);

        // Mic
        micButton = iconButton(
            "🎙",
            "Start/stop voice recording",
            MobileUiKit.ActionStyle.SECONDARY
        );

        micButton.setOnClickListener(ignored -> {
            if (isRecording) {
                stopVoiceRecording();
            } else if (hasPendingComposerSend()) {
                sendComposerText(micButton);
            } else {
                startVoiceRecording();
            }
        });
        styleComposerEmbeddedAction(micButton);

        actionRow.addView(
            micButton,
            composerTopAction()
        );

        // Upload
        Button uploadAudio = iconButton(
            "📎",
            "Upload audio file for transcription",
            MobileUiKit.ActionStyle.SECONDARY
        );

        uploadAudio.setOnClickListener(
            ignored -> openAudioFilePicker()
        );
        styleComposerEmbeddedAction(uploadAudio);

        actionRow.addView(
            uploadAudio,
            composerTopAction()
        );

        // Options
        Button options = iconButton(
            "⋮",
            "More chat options",
            MobileUiKit.ActionStyle.SECONDARY
        );

        options.setTextSize(20);

        options.setOnClickListener(
            ignored -> showChatOptions(options)
        );
        styleComposerEmbeddedAction(options);

        actionRow.addView(
            options,
            composerTopAction()
        );

        composerTopRow.addView(
            actionRow,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(52)
            )
        );

        composerDock.addView(
            composerTopRow,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );

        // =========================================================
        // Send button BELOW everything
        // =========================================================

        Button send = iconButton(
            "➤",
            "Send message",
            MobileUiKit.ActionStyle.PRIMARY
        );

        send.setText("➤  Send");
        send.setTextSize(15);

        sendButton = micButton;

        send.setOnClickListener(
            ignored -> sendComposerText(send)
        );

        LinearLayout.LayoutParams sendParams =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            );

        sendParams.setMargins(
            0,
            dp(8),
            0,
            0
        );

        send.setVisibility(View.GONE);
        composerDock.addView(send, sendParams);

        panel.addView(
            composerDock,
            matchWrap()
        );

        renderTranscript();
        updateComposerPrimaryAction();
        return panel;
    }

    private LinearLayout.LayoutParams composerTopAction() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                dp(52),
                dp(52)
            );

        return params;
    }

    private void styleComposerEmbeddedAction(Button button) {
        button.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        );
        button.setMinWidth(0);
    }

    private boolean hasPendingComposerSend() {
        return pendingAudioAttachment != null
            || (promptEditText != null && !promptEditText.getText().toString().trim().isEmpty());
    }

    private void updateComposerPrimaryAction() {
        if (micButton == null || isRecording) return;
        boolean sendMode = hasPendingComposerSend();
        micButton.setText(sendMode ? "➤" : "🎙");
        micButton.setContentDescription(sendMode ? "Send message" : "Start voice recording");
        micButton.setTooltipText(sendMode ? "Send message" : "Record audio");
    }

    private void showChatOptions(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);

        menu.getMenu().add(
            0,
            1,
            0,
            "↻  Sync Matrix conversation"
        );

        menu.getMenu().add(
            0,
            2,
            1,
            "⌫  Clear local conversation"
        );

        menu.getMenu().add(
            0,
            3,
            2,
            "Chat Studio settings"
        );

        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Button control = (Button) anchor;
                synchronize(control);
            } else if (item.getItemId() == 2) {
                history.clear();
                saveHistory();
                renderTranscript();
                status.accept("Local conversation cleared.");
            } else {
                showChatSettings();
            }

            return true;
        });

        menu.show();
    }

    private void showChatSettings() {
        LinearLayout settings = new LinearLayout(activity);
        settings.setOrientation(LinearLayout.VERTICAL);

        CheckBox autoSend = new CheckBox(activity);
        autoSend.setText(
            "Automatically send voice transcriptions"
        );
        autoSend.setTextColor(ui.textColor());
        autoSend.setChecked(autoSendVoiceTranscripts());
        autoSend.setPadding(
            dp(20),
            dp(12),
            dp(20),
            dp(12)
        );

        CheckBox autoSendRecording = new CheckBox(activity);
        autoSendRecording.setText(
            "Automatically send recording on stop"
        );
        autoSendRecording.setTextColor(ui.textColor());
        autoSendRecording.setChecked(
            autoSendRecordingOnStop()
        );
        autoSendRecording.setPadding(
            dp(20),
            dp(12),
            dp(20),
            dp(12)
        );

        CheckBox autoReplyTts = new CheckBox(activity);
        autoReplyTts.setText(
            "Automatically read LazyDev replies aloud"
        );
        autoReplyTts.setTextColor(ui.textColor());
        autoReplyTts.setChecked(
            autoReplyTtsEnabled()
        );
        autoReplyTts.setPadding(
            dp(20),
            dp(12),
            dp(20),
            dp(12)
        );

        android.widget.Spinner ttsMode =
            new android.widget.Spinner(activity);

        ttsMode.setAdapter(
            new StyledSpinnerAdapter<>(
                activity,
                List.of(
                    "Built-in Android",
                    "ComfyUI tts.json"
                )
            )
        );

        ttsMode.setSelection(
            "comfyui".equals(textToSpeechMode()) ? 1 : 0
        );

        settings.addView(
            autoSendRecording,
            matchWrap()
        );

        settings.addView(
            autoSend,
            matchWrap()
        );

        settings.addView(
            autoReplyTts,
            matchWrap()
        );

        settings.addView(
            ui.overline("Chat model provider"),
            matchWrap()
        );

        TextView providerInfo = new TextView(activity);
        providerInfo.setText(
            "Dashboard local model (shared LazyDev / URage NOW prompt). " +
            "ChatGPT, Gemini, and Meshy will be configured on the dashboard so this app never stores provider API keys."
        );
        providerInfo.setTextColor(ui.textMutedColor());
        providerInfo.setPadding(dp(20), dp(4), dp(20), dp(12));
        settings.addView(providerInfo, matchWrap());

        settings.addView(
            ui.overline("Reply text to speech mode"),
            matchWrap()
        );

        settings.addView(
            ttsMode,
            matchWrap()
        );

        new android.app.AlertDialog.Builder(activity)
            .setTitle("Chat Studio settings")
            .setMessage(
                "Stopped recordings can start STT immediately. " +
                "Voice transcripts can then stay editable or send as chat messages."
            )
            .setView(settings)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                "Save",
                (dialog, ignored) -> {
                    preferences.edit()
                        .putBoolean(
                            AUTO_SEND_RECORDING_ON_STOP,
                            autoSendRecording.isChecked()
                        )
                        .putBoolean(
                            AUTO_SEND_VOICE_TRANSCRIPTS,
                            autoSend.isChecked()
                        )
                        .putBoolean(
                            AUTO_REPLY_TTS,
                            autoReplyTts.isChecked()
                        )
                        .putString(
                            TTS_MODE,
                            ttsMode.getSelectedItemPosition() == 1
                                ? "comfyui"
                                : "builtin"
                        )
                        .apply();

                    status.accept(
                        autoSendRecording.isChecked()
                            ? "Stopped recordings will start transcription automatically."
                            : "Stopped recordings will remain attached until you tap Send."
                    );
                }
            )
            .show();
    }

    private boolean autoSendVoiceTranscripts() {
        // Keep the transcript visible and editable by default. Auto-send is an
        // explicit Chat Studio preference because it otherwise clears the
        // composer immediately after a successful STT relay.
        return preferences.getBoolean(
            AUTO_SEND_VOICE_TRANSCRIPTS,
            false
        );
    }

    private boolean autoSendRecordingOnStop() {
        return preferences.getBoolean(
            AUTO_SEND_RECORDING_ON_STOP,
            true
        );
    }

    private boolean autoReplyTtsEnabled() {
        return preferences.getBoolean(
            AUTO_REPLY_TTS,
            false
        );
    }

    private String textToSpeechMode() {
        return preferences.getString(
            TTS_MODE,
            "builtin"
        );
    }

    private void synchronize(Button button) {
        button.setEnabled(false);

        status.accept(
            "Synchronizing the encrypted Matrix conversation…"
        );

        executor.execute(() -> {
            try {
                List<DashboardApi.ChatMessage> synchronizedHistory =
                    requiredMatrixRelay()
                        .synchronizedChatHistory(20);

                main.post(() -> {
                    history.clear();
                    history.addAll(synchronizedHistory);
                    saveHistory();
                    renderTranscript();
                    button.setEnabled(true);
                    status.accept(
                        "Matrix conversation synchronized."
                    );
                });
            } catch (Exception error) {
                main.post(() -> {
                    button.setEnabled(true);
                    status.accept(
                        message(
                            error,
                            "Matrix synchronization failed."
                        )
                    );
                });
            }
        });
    }

    private void renderTranscript() {
        transcript.render(history);
        scrollToConversationEnd();
    }

    private void scrollToConversationEnd() {
        transcriptScroll.post(
            () -> transcriptScroll.fullScroll(
                View.FOCUS_DOWN
            )
        );
    }

    private MatrixSdkRelayClient requiredMatrixRelay() {
        MatrixSdkRelayClient relay = matrixRelay.get();

        if (relay == null) {
            throw new IllegalStateException(
                "Configure the Matrix Internet Relay under Connection first."
            );
        }

        return relay;
    }

    private boolean usesMatrix() {
        return ConnectionRouteStore.MATRIX.equals(route.get());
    }

    private void loadHistory() {
        try {
            JSONArray entries =
                new JSONArray(
                    preferences.getString(
                        "chatHistory",
                        "[]"
                    )
                );

            for (
                int index = Math.max(
                    0,
                    entries.length() - 20
                );
                index < entries.length();
                index++
            ) {
                JSONObject entry =
                    entries.optJSONObject(index);

                if (entry != null) {
                    history.add(
                        new DashboardApi.ChatMessage(
                            entry.optString("role"),
                            entry.optString("content")
                        )
                    );
                }
            }
        } catch (Exception ignored) {
            history.clear();
        }
    }

    private void saveHistory() {
        JSONArray entries = new JSONArray();

        for (DashboardApi.ChatMessage message : history) {
            try {
                entries.put(
                    new JSONObject()
                        .put(
                            "role",
                            message.role()
                        )
                        .put(
                            "content",
                            persistedContent(
                                message.content()
                            )
                        )
                );
            } catch (Exception ignored) {}
        }

        preferences
            .edit()
            .putString(
                "chatHistory",
                entries.toString()
            )
            .apply();
    }

    private String persistedContent(String content) {
        String value = String.valueOf(content);

        if (value.length() <= MAX_PERSISTED_MESSAGE_CHARS) {
            return value;
        }

        return value.substring(
            0,
            MAX_PERSISTED_MESSAGE_CHARS
        )
            + "\n\n[Long message kept only for this session; "
            + "the stored transcript was shortened.]";
    }

    private TextView text(
        String value,
        int size,
        int color
    ) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.15f);
        return text;
    }

    private String message(
        Exception error,
        String fallback
    ) {
        return error.getMessage() == null
            ? fallback
            : error.getMessage();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
            );

        params.setMargins(
            0,
            0,
            dp(6),
            0
        );

        return params;
    }

    private LinearLayout.LayoutParams weightedHeight() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1
        );
    }

    private LinearLayout.LayoutParams iconAction() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                dp(52),
                dp(52)
            );

        params.setMargins(
            0,
            0,
            dp(6),
            0
        );

        return params;
    }

    /*
     * The message field occupies the large left area.
     */
    private LinearLayout.LayoutParams composerInput() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                0,
                dp(156),
                1
            );

        params.setMargins(
            0,
            0,
            dp(6),
            0
        );

        return params;
    }

    /*
     * Three 48dp buttons stacked vertically.
     *
     *  🎙
     *  📎
     *  ⋮
     */
    private LinearLayout.LayoutParams composerRail() {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                dp(48),
                dp(156)
            );

        params.setMargins(
            0,
            0,
            dp(6),
            0
        );

        return params;
    }

    private Button iconButton(
        String icon,
        String description,
        MobileUiKit.ActionStyle style
    ) {
        Button button = ui.button(
            icon,
            style
        );

        button.setTextSize(22);
        button.setContentDescription(description);
        button.setTooltipText(description);

        return button;
    }

    private int dp(int value) {
        return ui.dp(value);
    }

    // =========================================================
    // Voice Recording & Audio Upload Integration
    // =========================================================

    void startVoiceRecording() {
        if (isRecording) {
            stopVoiceRecording();
            return;
        }

        DashboardApi api = dashboardApi.get();

        if (usesMatrix() && matrixRelay.get() == null) {
            main.post(
                () -> status.accept(
                    "Configure the Matrix Internet Relay under Connection first."
                )
            );
            return;
        }

        if (!usesMatrix() && api == null) {
            main.post(
                () -> status.accept(
                    "Pair with the dashboard first (Connection tab) to transcribe voice recordings."
                )
            );
            return;
        }

        boolean hasPermission =
            activity.checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            )
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        if (!hasPermission) {
            try {
                activity.requestPermissions(
                    new String[]{
                        android.Manifest.permission.RECORD_AUDIO
                    },
                    RECORDING_PERMISSION_REQUEST
                );
            } catch (Exception e) {
                main.post(
                    () -> status.accept(
                        "Failed to request microphone permission: "
                            + e.getMessage()
                    )
                );
            }
        } else {
            beginRecording();
        }
    }

    private void beginRecording() {
        boolean hasPermission =
            activity.checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            )
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        if (!hasPermission) {
            main.post(
                () -> status.accept(
                    "Microphone permission was denied. Please grant it in Settings."
                )
            );
            return;
        }

        try {
            currentRecordingFile =
                new File(
                    activity.getCacheDir(),
                    "chat-recording-"
                        + UUID.randomUUID()
                        + ".m4a"
                );

            mediaRecorder = new MediaRecorder();

            mediaRecorder.setAudioSource(
                android.media.MediaRecorder.AudioSource.MIC
            );

            mediaRecorder.setOutputFormat(
                MediaRecorder.OutputFormat.MPEG_4
            );

            mediaRecorder.setAudioEncoder(
                MediaRecorder.AudioEncoder.AAC
            );

            mediaRecorder.setAudioEncodingBitRate(
                128000
            );

            mediaRecorder.setAudioSamplingRate(
                44100
            );

            mediaRecorder.setOutputFile(
                currentRecordingFile.getAbsolutePath()
            );

            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;

            main.post(() -> {
                recordingIndicator.setVisibility(
                    View.VISIBLE
                );

                micButton.setText("⏹");

                micButton.setContentDescription(
                    "Stop recording"
                );

                status.accept(
                    "● Recording... Tap to stop and transcribe."
                );
            });
        } catch (Exception error) {
            final String errorMsg =
                "Failed to start recording: "
                    + message(
                        error,
                        "unknown error"
                    );

            main.post(() -> {
                status.accept(errorMsg);

                recordingIndicator.setVisibility(
                    View.GONE
                );

                micButton.setText("🎙");

                micButton.setContentDescription(
                    "Record voice message (hold to record)"
                );
            });

            cleanupRecorder();
        }
    }

    void stopVoiceRecording() {
        if (!isRecording || mediaRecorder == null) {
            return;
        }

        File recordingFile =
            currentRecordingFile;

        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
        } catch (Exception error) {
            main.post(
                () -> status.accept(
                    "Error stopping recording: "
                        + message(
                            error,
                            "unknown error"
                        )
                )
            );

            cleanupRecorder();
            return;
        }

        isRecording = false;

        main.post(() -> {
            recordingIndicator.setVisibility(
                View.GONE
            );

            micButton.setText("🎙");

            micButton.setContentDescription(
                "Record voice message (hold to record)"
            );
        });

        cleanupRecorder();

        if (recordingFile == null) {
            status.accept(
                "Recording was not saved. Try again."
            );
            return;
        }

        if (
            !recordingFile.isFile()
                || recordingFile.length() == 0L
        ) {
            status.accept(
                "Recording is empty. Hold Record for a moment before stopping it."
            );
            return;
        }

        pendingAudioAttachment = recordingFile;

        main.post(() -> {
            audioAttachmentLabel.setText(
                "Audio attached — "
                    + recordingFile.getName()
            );

            audioAttachmentLabel.setVisibility(
                View.VISIBLE
            );

            updateComposerPrimaryAction();

            status.accept(
                "Audio attached. Tap Send to transcribe it."
            );

            if (
                autoSendRecordingOnStop()
                    && sendButton != null
            ) {
                sendComposerText(sendButton);
            }
        });
    }

    private void cleanupRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {}

            mediaRecorder = null;
        }

        isRecording = false;

        main.post(() -> {
            recordingIndicator.setVisibility(
                View.GONE
            );

            micButton.setText("🎙");

            micButton.setContentDescription(
                "Record voice message (hold to record)"
            );

            updateComposerPrimaryAction();
        });
    }

    private void openAudioFilePicker() {
        Intent intent =
            new Intent(
                Intent.ACTION_GET_CONTENT
            );

        intent.setType("audio/*");
        intent.addCategory(
            Intent.CATEGORY_OPENABLE
        );

        activity.startActivityForResult(
            Intent.createChooser(
                intent,
                "Select audio file"
            ),
            REQUEST_AUDIO_FILE
        );
    }

    void handleAudioPickResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        if (
            resultCode != Activity.RESULT_OK
                || data == null
                || data.getData() == null
        ) {
            return;
        }

        Uri uri = data.getData();

        status.accept(
            "Picked audio. Transcribing via STT..."
        );

        executor.execute(
            () -> transcribeUriIntoComposer(uri)
        );
    }

    private void transcribeRecordedAudio(
        File audioFile,
        Button send,
        AudioMessageRequest audioMessage
    ) {
        if (!audioFile.exists()) {
            main.post(() -> {
                send.setEnabled(true);
                status.accept(
                    "Recording file not found. Try again."
                );
            });
            return;
        }

        boolean delivered = false;

        try {
            main.post(
                () -> status.accept(
                    usesMatrix()
                        ? "Uploading audio to Matrix..."
                        : "Uploading audio to the dashboard STT workflow..."
                )
            );

            String transcriptText =
                usesMatrix()
                    ? requiredMatrixRelay()
                        .transcribeAudio(audioFile)
                    : sttTranscribe(audioFile);

            if (
                transcriptText == null
                    || transcriptText.trim().isEmpty()
            ) {
                main.post(() -> {
                    updateAudioMessage(
                        audioMessage,
                        "Audio message\nTranscription returned no text."
                    );

                    send.setEnabled(true);

                    status.accept(
                        "STT returned empty text. The audio is still attached; tap Send to retry."
                    );
                });

                return;
            }

            delivered = true;

            clearPendingAudioAttachment(
                audioFile
            );

            handleVoiceTranscript(
                transcriptText,
                send,
                audioMessage
            );
        } catch (Exception error) {
            final String msg =
                message(
                    error,
                    "unknown error"
                );

            main.post(() -> {
                updateAudioMessage(
                    audioMessage,
                    "Audio message\nTranscription failed."
                );

                send.setEnabled(true);

                status.accept(
                    "Audio delivery/transcription failed: "
                        + msg
                );
            });
        } finally {
            if (
                delivered
                    && audioFile.exists()
                    && !audioFile.delete()
            ) {
                audioFile.deleteOnExit();
            }
        }
    }

    private void clearPendingAudioAttachment(
        File deliveredFile
    ) {
        main.post(() -> {
            if (
                pendingAudioAttachment != null
                    && pendingAudioAttachment
                        .getAbsolutePath()
                        .equals(
                            deliveredFile.getAbsolutePath()
                        )
            ) {
                pendingAudioAttachment = null;

                audioAttachmentLabel.setVisibility(
                    View.GONE
                );

                updateComposerPrimaryAction();
            }
        });
    }

    private void transcribeUriIntoComposer(
        Uri uri
    ) {
        try {
            ByteArrayOutputStream baos =
                new ByteArrayOutputStream();

            try (
                InputStream is =
                    activity
                        .getContentResolver()
                        .openInputStream(uri)
            ) {
                if (is == null) {
                    throw new IOException(
                        "Could not open selected audio."
                    );
                }

                byte[] buffer =
                    new byte[4096];

                int read;

                while (
                    (read = is.read(buffer))
                        != -1
                ) {
                    baos.write(
                        buffer,
                        0,
                        read
                    );
                }
            }

            byte[] audioBytes =
                baos.toByteArray();

            String base64Audio =
                Base64.encodeToString(
                    audioBytes,
                    Base64.NO_WRAP
                );

            String mimeType =
                activity
                    .getContentResolver()
                    .getType(uri);

            String dataUrl =
                "data:"
                    + (
                        mimeType != null
                            ? mimeType
                            : "audio/webm"
                    )
                    + ";base64,"
                    + base64Audio;

            String transcriptText =
                sttTranscribeFromDataUrl(
                    dataUrl,
                    "picked-audio.webm"
                );

            if (
                transcriptText == null
                    || transcriptText.trim().isEmpty()
            ) {
                main.post(
                    () -> status.accept(
                        "STT returned empty text. Try again."
                    )
                );
                return;
            }

            showTranscriptInComposer(
                transcriptText,
                null
            );
        } catch (Exception error) {
            main.post(
                () -> status.accept(
                    "STT transcription failed: "
                        + message(
                            error,
                            "unknown error"
                        )
                )
            );
        }
    }

    private String sttTranscribe(
        File audioFile
    ) throws Exception {
        if (!audioFile.exists()) {
            throw new IOException(
                "Recording file does not exist: "
                    + audioFile.getAbsolutePath()
            );
        }

        ByteArrayOutputStream baos =
            new ByteArrayOutputStream();

        try (
            java.io.FileInputStream fis =
                new java.io.FileInputStream(
                    audioFile
                )
        ) {
            byte[] buffer =
                new byte[8192];

            int read;

            while (
                (read = fis.read(buffer))
                    != -1
            ) {
                baos.write(
                    buffer,
                    0,
                    read
                );
            }
        }

        byte[] audioBytes =
            baos.toByteArray();

        if (audioBytes.length == 0) {
            throw new IOException(
                "Recording file is empty"
            );
        }

        String base64Audio =
            Base64.encodeToString(
                audioBytes,
                Base64.NO_WRAP
            );

        String extension = "";

        if (
            audioFile.getName().endsWith(".m4a")
                || audioFile.getName().endsWith(".mp4")
        ) {
            extension = "audio/mp4";
        } else if (
            audioFile.getName().endsWith(".webm")
        ) {
            extension = "audio/webm";
        } else {
            extension = "audio/mpeg";
        }

        return sttTranscribeFromDataUrl(
            "data:"
                + extension
                + ";base64,"
                + base64Audio,
            audioFile.getName()
        );
    }

    private String sttTranscribeFromDataUrl(
        String audioDataUrl,
        String fileName
    ) throws Exception {
        DashboardApi api =
            dashboardApi.get();

        if (api == null) {
            throw new IllegalStateException(
                "STT requires a LAN-paired dashboard. "
                    + "Go to Connection tab and pair first."
            );
        }

        return api.transcribeSpeechToText(
            audioDataUrl,
            fileName
        );
    }

    private void handleVoiceTranscript(
        String transcriptText,
        Button send,
        AudioMessageRequest audioMessage
    ) {
        String text =
            transcriptText.trim();

        main.post(
            () -> updateAudioMessage(
                audioMessage,
                text
            )
        );

        if (!autoSendVoiceTranscripts()) {
            showTranscriptInComposer(
                text,
                send
            );
            return;
        }

        main.post(() -> {
            status.accept(
                "Voice transcribed. Sending message…"
            );

            dispatchMessage(
                text,
                audioMessage.context(),
                send
            );
        });
    }

    private void showTranscriptInComposer(
        String transcriptText,
        Button send
    ) {
        String text =
            transcriptText.trim();

        main.post(() -> {
            if (promptEditText != null) {
                promptEditText.setText(text);
                promptEditText.setSelection(
                    text.length()
                );
                promptEditText.requestFocus();
            }

            if (send != null) {
                send.setEnabled(true);
            }

            String preview =
                text.substring(
                    0,
                    Math.min(80, text.length())
                );

            status.accept(
                "Transcribed: \""
                    + preview
                    + (
                        text.length() > 80
                            ? "..."
                            : ""
                    )
                    + "\""
            );
        });
    }

    private void sendComposerText(
        Button send
    ) {
        String text =
            promptEditText
                .getText()
                .toString()
                .trim();

        if (pendingAudioAttachment != null) {
            File audioFile =
                pendingAudioAttachment;

            AudioMessageRequest audioMessage =
                appendAudioMessage(audioFile);

            send.setEnabled(false);

            status.accept(
                usesMatrix()
                    ? "Sending attached audio to Matrix for transcription…"
                    : "Transcribing attached audio with the STT workflow…"
            );

            executor.execute(
                () -> transcribeRecordedAudio(
                    audioFile,
                    send,
                    audioMessage
                )
            );

            return;
        }

        if (text.isEmpty()) {
            status.accept(
                "Enter a Chat Studio message or attach audio first."
            );
            return;
        }

        sendMessage(
            text,
            send
        );
    }

    void setPromptText(String text) {
        main.post(() -> {
            if (promptEditText != null) {
                promptEditText.setText(text);

                // Scroll to bottom of the input so user sees where text was added
                promptEditText.setSelection(
                    promptEditText.length()
                );
            }
        });
    }

    void clearPrompt() {
        main.post(() -> {
            if (promptEditText != null) {
                promptEditText.setText("");
            }
        });
    }

    private void sendMessage(
        String text,
        Button send
    ) {
        boolean useMatrix =
            usesMatrix();

        DashboardApi api =
            useMatrix
                ? null
                : dashboardApi.get();

        if (!useMatrix && api == null) {
            return;
        }

        List<DashboardApi.ChatMessage> context =
            new ArrayList<>(history);

        history.add(
            new DashboardApi.ChatMessage(
                "user",
                text
            )
        );

        saveHistory();

        if (promptEditText != null) {
            promptEditText.setText("");
        }

        dispatchMessage(
            text,
            context,
            send
        );
    }

    private void dispatchMessage(
        String text,
        List<DashboardApi.ChatMessage> context,
        Button send
    ) {
        boolean useMatrix =
            usesMatrix();

        DashboardApi api =
            useMatrix
                ? null
                : dashboardApi.get();

        if (!useMatrix && api == null) {
            return;
        }

        transcript.renderPendingReply(
            history,
            ""
        );

        scrollToConversationEnd();

        send.setEnabled(false);

        status.accept(
            "Waiting for Chat Studio…"
        );

        executor.execute(() -> {
            try {
                StringBuilder partial =
                    new StringBuilder();

                Consumer<String> stream =
                    useMatrix
                        ? ignored -> {}
                        : delta -> {
                            partial.append(delta);

                            main.post(() -> {
                                transcript.renderPendingReply(
                                    history,
                                    partial.toString()
                                );

                                scrollToConversationEnd();
                            });
                        };

                String reply =
                    useMatrix
                        ? requiredMatrixRelay()
                            .chatStream(
                                text,
                                stream
                            )
                        : api.chatStream(
                            context,
                            text,
                            stream
                        );

                history.add(
                    new DashboardApi.ChatMessage(
                        "assistant",
                        reply
                    )
                );

                while (history.size() > 20) {
                    history.remove(0);
                }

                saveHistory();

                main.post(() -> {
                    renderTranscript();

                    send.setEnabled(true);

                    status.accept(
                        "Chat reply received."
                    );

                    if (autoReplyTtsEnabled()) {
                        speakAssistantReply(reply);
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    renderTranscript();

                    send.setEnabled(true);

                    status.accept(
                        message(
                            error,
                            "Chat request failed."
                        )
                    );
                });
            }
        });
    }

    private AudioMessageRequest appendAudioMessage(
        File audioFile
    ) {
        List<DashboardApi.ChatMessage> context =
            new ArrayList<>(history);

        history.add(
            new DashboardApi.ChatMessage(
                "user",
                "Audio attachment: "
                    + audioFile.getName()
                    + "\nTranscribing…"
            )
        );

        saveHistory();
        renderTranscript();

        return new AudioMessageRequest(
            history.size() - 1,
            context
        );
    }

    private void updateAudioMessage(
        AudioMessageRequest audioMessage,
        String content
    ) {
        if (
            audioMessage == null
                || audioMessage.historyIndex() < 0
                || audioMessage.historyIndex()
                    >= history.size()
        ) {
            return;
        }

        history.set(
            audioMessage.historyIndex(),
            new DashboardApi.ChatMessage(
                "user",
                content
            )
        );

        saveHistory();
        renderTranscript();
    }

    private void speakAssistantReply(
        String text
    ) {
        if (!"comfyui".equals(textToSpeechMode())) {
            speechPlayer.speakBuiltIn(text);
            return;
        }

        DashboardApi api =
            dashboardApi.get();

        if (api == null) {
            status.accept(
                "ComfyUI TTS needs a LAN-paired dashboard; using built-in Android speech."
            );

            speechPlayer.speakBuiltIn(text);
            return;
        }

        status.accept(
            "Generating ComfyUI speech…"
        );

        executor.execute(() -> {
            try {
                DashboardApi.SpeechSynthesis speech =
                    api.synthesizeSpeech(text);

                main.post(() -> {
                    try {
                        speechPlayer.playGeneratedAudio(
                            speech.audioDataUrl()
                        );

                        status.accept(
                            "Reading LazyDev reply with ComfyUI TTS."
                        );
                    } catch (Exception error) {
                        status.accept(
                            "ComfyUI TTS playback failed; using built-in Android speech."
                        );

                        speechPlayer.speakBuiltIn(
                            text
                        );
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    status.accept(
                        "ComfyUI TTS failed; using built-in Android speech."
                    );

                    speechPlayer.speakBuiltIn(
                        text
                    );
                });
            }
        });
    }

    void close() {
        speechPlayer.close();
    }

    @SuppressWarnings("unused")
    void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        if (
            requestCode
                == RECORDING_PERMISSION_REQUEST
        ) {
            if (
                grantResults.length > 0
                    && grantResults[0]
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                beginRecording();
            } else {
                main.post(
                    () -> status.accept(
                        "Microphone permission denied."
                    )
                );
            }
        }
    }

    @SuppressWarnings("unused")
    void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        if (
            requestCode
                == REQUEST_AUDIO_FILE
        ) {
            handleAudioPickResult(
                requestCode,
                resultCode,
                data
            );
        }
    }

    private static final int MAX_PERSISTED_MESSAGE_CHARS =
        24_000;
}
