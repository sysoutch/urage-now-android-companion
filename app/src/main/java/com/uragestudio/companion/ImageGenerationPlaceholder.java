package com.uragestudio.companion;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Theme-aware animated halftone surface for an image job that has not produced media yet. */
final class ImageGenerationPlaceholder extends FrameLayout {
    private final MobileUiKit ui;
    private final HalftoneField field;

    ImageGenerationPlaceholder(Activity activity) {
        super(activity);
        ui = new MobileUiKit(activity);
        setBackground(ui.controlBackground());
        setContentDescription("Image generation in progress");
        field = new HalftoneField(activity, ui);
        addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView label = ui.overline("Generating image");
        label.setGravity(Gravity.TOP | Gravity.START);
        label.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0);
        addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView detail = ui.body("Shaping pixels from your prompt");
        detail.setTextColor(ui.textMutedColor());
        detail.setGravity(Gravity.BOTTOM | Gravity.START);
        detail.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(12));
        LayoutParams detailLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(detail, detailLayout);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        field.start();
    }

    @Override protected void onDetachedFromWindow() {
        field.stop();
        super.onDetachedFromWindow();
    }

    private static final class HalftoneField extends View {
        private final MobileUiKit ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
        private float phase;

        HalftoneField(Activity activity, MobileUiKit ui) {
            super(activity);
            this.ui = ui;
            pulse.setDuration(1_500);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
        }

        void start() { if (!pulse.isStarted()) pulse.start(); }
        void stop() { pulse.cancel(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float spacing = ui.dp(22);
            float originX = getWidth() * 0.50f;
            float originY = getHeight() * 0.58f;
            for (int row = -6; row <= 6; row++) {
                for (int column = -7; column <= 7; column++) {
                    float x = originX + column * spacing;
                    float y = originY + row * spacing;
                    float distance = (float) Math.sqrt(column * column + row * row);
                    float intensity = Math.max(0f, 1f - distance / 8.5f);
                    float wave = 0.62f + 0.38f * (float) Math.sin((distance * 0.9f) - phase * Math.PI * 2);
                    float radius = ui.dp(1) + ui.dp(4) * intensity * wave;
                    int alpha = Math.round(28 + 148 * intensity * wave);
                    paint.setColor(Color.argb(alpha, Color.red(ui.accentStrongColor()), Color.green(ui.accentStrongColor()), Color.blue(ui.accentStrongColor())));
                    canvas.drawCircle(x, y, radius, paint);
                }
            }
        }
    }
}
