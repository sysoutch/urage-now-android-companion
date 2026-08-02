package com.uragestudio.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

final class AudioWaveformView extends View {
    private final Paint inactive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] amplitudes = new float[0];
    private float progress;

    AudioWaveformView(Context context) {
        super(context);
        MobileUiKit ui = new MobileUiKit(context);
        inactive.setColor(ui.textMutedColor());
        inactive.setStrokeWidth(dp(3));
        active.setColor(ui.accentStrongColor());
        active.setStrokeWidth(dp(3));
        setMinimumHeight(dp(104));
    }

    void setAmplitudes(float[] values) {
        amplitudes = values == null ? new float[0] : values;
        invalidate();
    }

    void setProgress(float value) {
        progress = Math.max(0, Math.min(1, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (amplitudes.length == 0) return;
        float width = getWidth();
        float center = getHeight() / 2f;
        float step = width / amplitudes.length;
        for (int index = 0; index < amplitudes.length; index++) {
            float x = (index + 0.5f) * step;
            float halfHeight = Math.max(dp(2), amplitudes[index] * center * 0.88f);
            canvas.drawLine(x, center - halfHeight, x, center + halfHeight,
                index / (float) amplitudes.length <= progress ? active : inactive);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
