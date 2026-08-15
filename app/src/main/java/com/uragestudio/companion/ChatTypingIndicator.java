package com.uragestudio.companion;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** A small, lifecycle-aware three-dot wave shown while Chat is awaiting a reply. */
final class ChatTypingIndicator extends LinearLayout {
    private final MobileUiKit ui;
    private final List<ObjectAnimator> waves = new ArrayList<>();

    ChatTypingIndicator(Activity activity) {
        super(activity);
        ui = new MobileUiKit(activity);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(ui.dp(58));
        setPadding(0, ui.dp(17), 0, ui.dp(14));
        addDot(ui.accentStrongColor(), 0);
        addDot(ui.accentColor(), 140);
        addDot(ui.textMutedColor(), 280);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (ObjectAnimator wave : waves) wave.start();
    }

    @Override protected void onDetachedFromWindow() {
        for (ObjectAnimator wave : waves) wave.cancel();
        super.onDetachedFromWindow();
    }

    private void addDot(int color, long startDelay) {
        TextView dot = new TextView(getContext());
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        dot.setBackground(background);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ui.dp(9), ui.dp(9));
        layout.setMargins(0, 0, ui.dp(6), 0);
        addView(dot, layout);
        ObjectAnimator wave = ObjectAnimator.ofFloat(dot, TRANSLATION_Y, 0f, -ui.dp(7), 0f);
        wave.setDuration(720);
        wave.setStartDelay(startDelay);
        wave.setRepeatCount(ObjectAnimator.INFINITE);
        waves.add(wave);
    }
}
