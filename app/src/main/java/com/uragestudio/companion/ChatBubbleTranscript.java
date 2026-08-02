package com.uragestudio.companion;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Renders persisted and streaming Chat messages as distinct conversational bubbles. */
final class ChatBubbleTranscript {
    private final Activity activity;
    private final MobileUiKit ui;
    private final LinearLayout container;

    ChatBubbleTranscript(Activity activity) {
        this.activity = activity;
        ui = new MobileUiKit(activity);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, ui.dp(6), 0, ui.dp(8));
    }

    LinearLayout view() {
        return container;
    }

    void render(List<DashboardApi.ChatMessage> history) {
        render(history, null, null);
    }

    void render(List<DashboardApi.ChatMessage> history, String pendingPrompt, String pendingReply) {
        container.removeAllViews();
        for (DashboardApi.ChatMessage message : history) {
            addBubble(message.role(), message.content(), false);
        }
        if (pendingPrompt != null) {
            addBubble("user", pendingPrompt, false);
            addBubble("assistant", pendingReply == null || pendingReply.isBlank() ? "Thinking…" : pendingReply, true);
        }
        if (container.getChildCount() == 0) {
            TextView empty = ui.body("No messages yet. Start a conversation below.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24));
            container.addView(empty, ui.matchWrap());
        }
    }

    private void addBubble(String role, String content, boolean streaming) {
        boolean assistant = "assistant".equals(role);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(assistant ? Gravity.START : Gravity.END);

        LinearLayout bubble = new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(ui.dp(13), ui.dp(9), ui.dp(13), ui.dp(11));
        bubble.setBackground(assistant ? ui.controlBackground() : ui.selectedControlBackground());

        TextView author = ui.overline(assistant ? (streaming ? "LazyDev · streaming" : "LazyDev") : "You");
        author.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bubble.addView(author, ui.matchWrap());

        TextView message = new TextView(activity);
        message.setText(MarkdownRichText.render(activity, String.valueOf(content)));
        message.setTextColor(ui.textColor());
        message.setTextSize(14);
        message.setLineSpacing(0, 1.15f);
        message.setPadding(0, ui.dp(4), 0, 0);
        message.setMaxWidth(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.78f));
        bubble.addView(message, ui.matchWrap());

        LinearLayout.LayoutParams bubbleLayout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bubbleLayout.setMargins(assistant ? 0 : ui.dp(34), ui.dp(5), assistant ? ui.dp(34) : 0, ui.dp(5));
        row.addView(bubble, bubbleLayout);
        container.addView(row, ui.matchWrap());
    }
}
