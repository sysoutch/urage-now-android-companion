package com.uragestudio.companion;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Renders persisted and in-flight Chat messages as distinct conversational bubbles. */
final class ChatBubbleTranscript {
    private final Activity activity;
    private final MobileUiKit ui;
    private final LinearLayout container;
    private int renderedMessageCount = -1;

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
        render(history, null);
    }

    void renderPendingReply(List<DashboardApi.ChatMessage> history, String pendingReply) {
        render(history, pendingReply);
    }

    private void render(List<DashboardApi.ChatMessage> history, String pendingReply) {
        container.removeAllViews();
        int existingMessageCount = renderedMessageCount;
        for (int index = 0; index < history.size(); index++) {
            DashboardApi.ChatMessage message = history.get(index);
            addBubble(message.role(), message.content(), false, existingMessageCount >= 0 && index >= existingMessageCount);
        }
        renderedMessageCount = history.size();
        if (pendingReply != null) {
            if (pendingReply.isBlank()) addTypingBubble();
            else addBubble("assistant", pendingReply, true, false);
        }
        if (container.getChildCount() == 0) {
            TextView empty = ui.body("No messages yet. Start a conversation below.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24));
            container.addView(empty, ui.matchWrap());
        }
    }

    private void addTypingBubble() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.START);
        LinearLayout bubble = new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(ui.dp(15), ui.dp(9), ui.dp(15), ui.dp(10));
        bubble.setBackground(ui.chatBubbleBackground(false));
        TextView author = ui.overline("LazyDev \u00b7 thinking");
        author.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bubble.addView(author, ui.matchWrap());
        bubble.addView(new ChatTypingIndicator(activity), ui.matchWrap());
        LinearLayout.LayoutParams bubbleLayout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bubbleLayout.setMargins(0, ui.dp(5), ui.dp(34), ui.dp(5));
        row.addView(bubble, bubbleLayout);
        container.addView(row, ui.matchWrap());
    }

    private void addBubble(String role, String content, boolean streaming, boolean animateIn) {
        boolean assistant = "assistant".equals(role);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(assistant ? Gravity.START : Gravity.END);

        LinearLayout bubble = new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12));
        bubble.setBackground(ui.chatBubbleBackground(!assistant));

        TextView author = ui.overline(assistant ? (streaming ? "LazyDev \u00b7 streaming" : "LazyDev") : "You");
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
        if (animateIn) animateBubbleIn(bubble);
    }

    private void animateBubbleIn(View bubble) {
        bubble.setAlpha(0f);
        bubble.setScaleX(0.94f);
        bubble.setScaleY(0.94f);
        bubble.setTranslationY(ui.dp(10));
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(new OvershootInterpolator(0.65f))
            .start();
    }
}
