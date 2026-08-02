package com.uragestudio.companion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * Shared visual language for Android Companion.
 *
 * Screen controllers own behavior; this class owns visual hierarchy, spacing,
 * colors, typography, and control variants so new features cannot drift back
 * toward unrelated platform defaults.
 */
final class MobileUiKit {
    enum ActionStyle { PRIMARY, SECONDARY, QUIET, DANGER }

    private final Context context;
    private final int background;
    private final int surface;
    private final int surfaceHigh;
    private final int border;
    private final int accent;
    private final int accentStrong;
    private final int accentContainer;
    private final int text;
    private final int textMuted;
    private final int danger;
    private final StudioThemeStore.Palette palette;

    MobileUiKit(Context context) {
        this.context = context;
        palette = new StudioThemeStore(context).active();
        background = palette.background();
        surface = palette.surface();
        surfaceHigh = palette.surfaceHigh();
        border = palette.border();
        accent = palette.accent();
        accentStrong = palette.accentStrong();
        accentContainer = palette.accentContainer();
        text = palette.text();
        textMuted = palette.textMuted();
        danger = palette.danger();
    }

    int backgroundColor() {
        return background;
    }

    int surfaceColor() { return surface; }
    int surfaceHighColor() { return surfaceHigh; }
    int borderColor() { return border; }
    int accentColor() { return accent; }
    int accentStrongColor() { return accentStrong; }
    int textColor() { return text; }
    int textMutedColor() { return textMuted; }
    boolean usesLightSystemBars() { return palette.light(); }

    TextView appTitle(String value) {
        TextView view = label(value, 25, text);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(-0.01f);
        return view;
    }

    TextView screenTitle(String value) {
        TextView view = label(value, 22, text);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    TextView sectionTitle(String value) {
        TextView view = label(value, 17, text);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(2));
        return view;
    }

    TextView overline(String value) {
        TextView view = label(value.toUpperCase(java.util.Locale.ROOT), 11, accentStrong);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(0.12f);
        return view;
    }

    TextView body(String value) {
        TextView view = label(value, 14, textMuted);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    TextView status(String value) {
        TextView view = label(value, 13, textMuted);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setCompoundDrawablePadding(dp(8));
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setBackground(rounded(surfaceHigh, border, 8));
        return view;
    }

    MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(surface);
        card.setStrokeColor(border);
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(10));
        card.setCardElevation(0);
        card.setUseCompatPadding(false);
        return card;
    }

    LinearLayout cardContent() {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        return content;
    }

    EditText input(String hint) {
        EditText view = new EditText(context);
        view.setHint(hint);
        view.setTextColor(text);
        view.setHintTextColor(textMuted);
        view.setTextSize(15);
        view.setSingleLine(false);
        view.setMinHeight(dp(52));
        view.setPadding(dp(13), dp(10), dp(13), dp(10));
        view.setBackground(rounded(surfaceHigh, border, 8));
        return view;
    }

    LinearLayout field(String label, String helper, View input) {
        LinearLayout field = new LinearLayout(context);
        field.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = label(label, 12, text);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setPadding(dp(2), 0, 0, dp(6));
        field.addView(labelView, matchWrap());
        field.addView(input, matchWrap());
        if (helper != null && !helper.isBlank()) {
            TextView helperView = label(helper, 11, textMuted);
            helperView.setPadding(dp(2), dp(5), dp(2), 0);
            field.addView(helperView, matchWrap());
        }
        return field;
    }

    MaterialButton button(String label, ActionStyle style) {
        MaterialButton button = new MaterialButton(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(48));
        button.setCornerRadius(dp(8));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        if (style == ActionStyle.PRIMARY) {
            button.setBackgroundTintList(ColorStateList.valueOf(accent));
            button.setTextColor(background);
            button.setStrokeWidth(0);
        } else if (style == ActionStyle.DANGER) {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            button.setTextColor(danger);
            button.setStrokeColor(ColorStateList.valueOf(Color.argb(150, Color.red(danger), Color.green(danger), Color.blue(danger))));
            button.setStrokeWidth(dp(1));
        } else if (style == ActionStyle.QUIET) {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            button.setTextColor(textMuted);
            button.setStrokeWidth(0);
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(accentContainer));
            button.setTextColor(text);
            button.setStrokeColor(ColorStateList.valueOf(Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent))));
            button.setStrokeWidth(dp(1));
        }
        return button;
    }

    GradientDrawable controlBackground() {
        return rounded(surfaceHigh, border, 8);
    }

    GradientDrawable selectedControlBackground() {
        return rounded(accentContainer, accent, 8);
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams spacedMatchWrap() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

}
