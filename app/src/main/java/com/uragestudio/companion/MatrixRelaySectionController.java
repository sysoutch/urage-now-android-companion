package com.uragestudio.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.google.android.material.card.MaterialCardView;
import java.util.function.Consumer;

/** Owns Matrix relay credential presentation, validation, persistence, and client creation. */
final class MatrixRelaySectionController {
    private final Activity activity;
    private final Consumer<String> status;
    private final Consumer<Exception> errors;
    private final SecureMatrixRelayStore store;
    private final MobileUiKit ui;
    private final MaterialCardView view;
    private EditText homeserver;
    private EditText accessToken;
    private EditText botUserId;
    private EditText roomId;
    private CheckBox allowUnencryptedMedia;

    MatrixRelaySectionController(Activity activity, Consumer<String> status, Consumer<Exception> errors) {
        this.activity = activity;
        this.status = status;
        this.errors = errors;
        store = new SecureMatrixRelayStore(activity);
        ui = new MobileUiKit(activity);
        view = build();
        restore();
    }

    View view() { return view; }
    void show(boolean visible) { view.setVisibility(visible ? View.VISIBLE : View.GONE); }

    MatrixSdkRelayClient client() {
        SecureMatrixRelayStore.Config config = store.load();
        if (config == null) {
            status.accept("Configure the Matrix Internet Relay under Connection first.");
            return null;
        }
        return new MatrixSdkRelayClient(activity, config);
    }

    private MaterialCardView build() {
        LinearLayout content = ui.cardContent();
        content.addView(ui.overline("Secure remote access"));
        content.addView(ui.sectionTitle("Matrix relay"));
        content.addView(ui.body("Continue Chat and every media Studio workflow through your private encrypted companion room."));
        homeserver = ui.input("https://matrix.example.com");
        accessToken = ui.input("Personal Matrix access token");
        accessToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        botUserId = ui.input("@urage-studio-bot:example.com");
        roomId = ui.input("!private-room:example.com");
        content.addView(ui.field("Homeserver", "HTTPS is required.", homeserver), ui.spacedMatchWrap());
        content.addView(ui.field("Your access token", "Use your personal account, never the bot token.", accessToken), ui.spacedMatchWrap());
        content.addView(ui.field("Bot user", "Messages are accepted only from this Matrix user.", botUserId), ui.spacedMatchWrap());
        content.addView(ui.field("Matrix room", "The personal user and bot must both be joined. Encrypted rooms are recommended.", roomId), ui.spacedMatchWrap());
        allowUnencryptedMedia = new CheckBox(activity);
        allowUnencryptedMedia.setText("Allow images in an unencrypted Matrix room");
        allowUnencryptedMedia.setTextColor(ui.textColor());
        content.addView(allowUnencryptedMedia, ui.spacedMatchWrap());
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = ui.button("Save relay", MobileUiKit.ActionStyle.PRIMARY);
        save.setOnClickListener(ignored -> save());
        Button forget = ui.button("Forget", MobileUiKit.ActionStyle.DANGER);
        forget.setOnClickListener(ignored -> {
            store.clear();
            accessToken.setText("");
            status.accept("Saved Matrix relay credentials removed.");
        });
        actions.addView(save, weighted());
        actions.addView(forget, weighted());
        content.addView(actions, ui.spacedMatchWrap());
        MaterialCardView card = ui.card();
        card.addView(content);
        return card;
    }

    private void save() {
        String homeserverUrl = ConnectionAddressNormalizer.normalize(homeserver.getText().toString());
        String token = accessToken.getText().toString().trim();
        String botUser = botUserId.getText().toString().trim();
        String room = roomId.getText().toString().trim();
        if (!homeserverUrl.startsWith("https://") || token.isEmpty() || !botUser.startsWith("@") || !room.startsWith("!")) {
            status.accept("Matrix relay needs an HTTPS homeserver, access token, bot user ID, and private room ID.");
            return;
        }
        try {
            if (allowUnencryptedMedia.isChecked()) {
                new AlertDialog.Builder(activity)
                    .setTitle("Unencrypted Matrix media")
                    .setMessage("Images in an unencrypted room can be read by the homeserver and everyone with room access. Are you sure? Only continue if you understand and accept these risks.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Yes, I understand the risks", (dialog, which) -> saveConfig(homeserverUrl, token, botUser, room, true))
                    .show();
                return;
            }
            saveConfig(homeserverUrl, token, botUser, room, false);
        } catch (Exception error) {
            errors.accept(error);
        }
    }

    private void restore() {
        SecureMatrixRelayStore.Config config = store.load();
        if (config == null) return;
        homeserver.setText(config.homeserverUrl());
        accessToken.setText(config.accessToken());
        botUserId.setText(config.botUserId());
        roomId.setText(config.roomId());
        allowUnencryptedMedia.setChecked(config.allowUnencryptedMedia());
    }

    private void saveConfig(String homeserverUrl, String token, String botUser, String room, boolean allowPlaintextMedia) {
        try {
            store.save(new SecureMatrixRelayStore.Config(homeserverUrl, token, botUser, room, allowPlaintextMedia));
            status.accept(allowPlaintextMedia
                ? "Matrix relay saved. Unencrypted-room media is enabled by your explicit confirmation."
                : "Matrix relay credentials encrypted with Android Keystore.");
        } catch (Exception error) {
            errors.accept(error);
        }
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }
}
