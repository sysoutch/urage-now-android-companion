package com.uragestudio.companion;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Home dashboard for URage Companion.
 *
 * This class intentionally uses standard Android widgets only so that the
 * home screen does not depend on any optional UI helpers.
 */
final class HomeWorkspaceController {

    private final Activity activity;
    private final Consumer<String> navigate;

    private final ScrollView view;
    private final TextView status;

    private final int background;
    private final int card;
    private final int cardAlt;
    private final int border;
    private final int text;
    private final int muted;
    private final int accent;
    private final int accentStrong;

    HomeWorkspaceController(Activity activity, MobileUiKit ui, Consumer<String> navigate) {
        this.activity = activity;
        this.navigate = navigate;
        background = ui.backgroundColor();
        card = ui.surfaceColor();
        cardAlt = ui.surfaceHighColor();
        border = ui.borderColor();
        text = ui.textColor();
        muted = ui.textMutedColor();
        accent = ui.accentColor();
        accentStrong = ui.accentStrongColor();

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(30));
        content.setBackgroundColor(background);

        // ---------------------------------------------------------
        // Header
        // ---------------------------------------------------------

        TextView eyebrow = label("URAGE COMPANION", 11, accent);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(eyebrow, wrap());

        TextView title = label("Welcome back", 30, text);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, margins(0, 4, 0, 2));

        TextView subtitle = label(
            "Your creative dashboard, right in your pocket.",
            14,
            muted
        );
        content.addView(subtitle, margins(0, 0, 0, 14));

        status = label("Not connected", 13, muted);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(12), 0, dp(12), 0);
        status.setBackground(roundBackground(cardAlt));
        content.addView(status, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ));

        // ---------------------------------------------------------
        // Stats
        // ---------------------------------------------------------

        content.addView(sectionTitle("Overview"), margins(0, 22, 0, 10));

        LinearLayout statsRow = new LinearLayout(activity);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);

        statsRow.addView(
            statCard("24", "Projects", accent),
            weight(1, 0, 0, 6, 0)
        );

        statsRow.addView(
            statCard("128", "Generations", accentStrong),
            weight(1, 0, 0, 6, 0)
        );

        statsRow.addView(
            statCard("12.4 GB", "Media", accent),
            weight(1, 0, 0, 0, 0)
        );

        content.addView(statsRow, wrap());

        // ---------------------------------------------------------
        // Activity graph
        // ---------------------------------------------------------

        content.addView(sectionTitle("Creative activity"), margins(0, 22, 0, 10));

        LinearLayout graphCard = cardContainer();

        TextView graphHeader = label(
            "Generation activity",
            15,
            text
        );
        graphHeader.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        graphCard.addView(graphHeader, wrap());

        TextView graphSubtitle = label(
            "Last 7 days",
            12,
            muted
        );
        graphCard.addView(graphSubtitle, margins(0, 3, 0, 8));

        ActivityGraph graph = new ActivityGraph(
            activity,
            new int[]{4, 8, 5, 13, 9, 17, 12},
            accent, border
        );

        graphCard.addView(graph, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(150)
        ));

        LinearLayout days = new LinearLayout(activity);
        days.setOrientation(LinearLayout.HORIZONTAL);
        days.setGravity(Gravity.CENTER);

        String[] dayNames = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

        for (String dayName : dayNames) {
            TextView dayLabel = label(dayName, 10, muted);
            dayLabel.setGravity(Gravity.CENTER);
            days.addView(dayLabel, new LinearLayout.LayoutParams(0, dp(22), 1));
        }

        graphCard.addView(days, wrap());

        content.addView(graphCard, wrap());

        // ---------------------------------------------------------
        // Studios
        // ---------------------------------------------------------

        content.addView(sectionTitle("Create"), margins(0, 22, 0, 10));

        content.addView(
            studioButton("💬", "Chat Studio", "Continue a conversation", "chat", accent),
            margins(0, 0, 0, 8)
        );

        content.addView(
            studioButton("🖼", "Image Studio", "Generate and edit images", "image", accentStrong),
            margins(0, 0, 0, 8)
        );

        content.addView(
            studioButton("🎙", "Audio Studio", "Speech, recording and transcription", "audio", accent),
            margins(0, 0, 0, 8)
        );

        content.addView(
            studioButton("🎵", "Music Studio", "Create and manage music", "music", accentStrong),
            margins(0, 0, 0, 8)
        );

        content.addView(
            studioButton("🎬", "Video Studio", "Generate and manage videos", "video", accent),
            margins(0, 0, 0, 8)
        );

        content.addView(
            studioButton("🧊", "3D Studio", "Create 3D models from your media", "model3d", accent),
            margins(0, 0, 0, 8)
        );

        // ---------------------------------------------------------
        // Media
        // ---------------------------------------------------------

        content.addView(sectionTitle("Your workspace"), margins(0, 18, 0, 10));

        LinearLayout mediaRow = new LinearLayout(activity);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);

        mediaRow.addView(
            compactButton("▣", "Gallery", "gallery"),
            weight(1, 0, 0, 6, 0)
        );

        mediaRow.addView(
            compactButton("⚙", "Tools", "tools"),
            weight(1, 0, 0, 0, 0)
        );

        content.addView(mediaRow, wrap());

        // ---------------------------------------------------------
        // Connection
        // ---------------------------------------------------------

        content.addView(sectionTitle("Dashboard"), margins(0, 22, 0, 10));

        LinearLayout connectionCard = cardContainer();

        TextView connectionTitle = label(
            "Dashboard connection",
            15,
            text
        );
        connectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        connectionCard.addView(connectionTitle, wrap());

        TextView connectionDescription = label(
            "Pair this phone with your URage dashboard or configure the Matrix relay.",
            12,
            muted
        );
        connectionDescription.setLineSpacing(0, 1.15f);
        connectionCard.addView(connectionDescription, margins(0, 5, 0, 12));

        Button connect = actionButton(
            "Connect dashboard",
            accent
        );

        connect.setOnClickListener(
            ignored -> navigate.accept("connection")
        );

        connectionCard.addView(connect, wrap());

        content.addView(connectionCard, wrap());

        // ---------------------------------------------------------
        // Footer
        // ---------------------------------------------------------

        TextView footer = label(
            "URage Studio Companion",
            11,
            muted
        );
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, margins(0, 26, 0, 0));

        // Scroll container
        view = new ScrollView(activity);
        view.setFillViewport(true);
        view.setBackgroundColor(background);
        view.addView(content);
        view.setVisibility(View.GONE);
    }

    // =============================================================
    // Public API expected by MainActivity
    // =============================================================

    View view() {
        return view;
    }

    TextView statusView() {
        return status;
    }

    void show(boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // =============================================================
    // UI helpers
    // =============================================================

    private TextView sectionTitle(String value) {
        TextView result = label(value, 18, text);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return result;
    }

    private TextView label(String value, float size, int color) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private LinearLayout statCard(
        String value,
        String title,
        int color
    ) {
        LinearLayout cardView = new LinearLayout(activity);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setGravity(Gravity.CENTER_VERTICAL);
        cardView.setPadding(dp(12), dp(12), dp(12), dp(12));
        cardView.setBackground(roundBackground(card));

        TextView number = label(value, 21, color);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView caption = label(title, 11, muted);

        cardView.addView(number, wrap());
        cardView.addView(caption, margins(0, 4, 0, 0));

        return cardView;
    }

    private LinearLayout studioButton(
        String icon,
        String title,
        String description,
        String destination,
        int color
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(11), dp(13), dp(11));
        row.setBackground(roundBackground(card));

        TextView iconView = label(icon, 24, color);
        iconView.setGravity(Gravity.CENTER);

        row.addView(iconView, new LinearLayout.LayoutParams(
            dp(44),
            dp(44)
        ));

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = label(title, 15, text);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView descriptionView = label(description, 11, muted);
        descriptionView.setSingleLine(true);

        textColumn.addView(titleView, wrap());
        textColumn.addView(descriptionView, margins(0, 2, 0, 0));

        LinearLayout.LayoutParams textParams =
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
            );

        textParams.setMargins(dp(10), 0, dp(8), 0);

        row.addView(textColumn, textParams);

        TextView arrow = label("›", 27, muted);
        arrow.setGravity(Gravity.CENTER);

        row.addView(arrow, new LinearLayout.LayoutParams(
            dp(28),
            dp(44)
        ));

        row.setOnClickListener(
            ignored -> navigate.accept(destination)
        );

        return row;
    }

    private LinearLayout compactButton(
        String icon,
        String title,
        String destination
    ) {
        LinearLayout button = new LinearLayout(activity);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(14), dp(10), dp(14));
        button.setBackground(roundBackground(card));

        TextView iconView = label(icon, 22, text);
        iconView.setGravity(Gravity.CENTER);

        TextView titleView = label(title, 12, muted);
        titleView.setGravity(Gravity.CENTER);

        button.addView(iconView, wrap());
        button.addView(titleView, margins(0, 4, 0, 0));

        button.setOnClickListener(
            ignored -> navigate.accept(destination)
        );

        return button;
    }

    private Button actionButton(String title, int color) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextColor(background);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(roundBackground(color));

        return button;
    }

    private LinearLayout cardContainer() {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(14), dp(14), dp(14), dp(14));
        container.setBackground(roundBackground(card));

        return container;
    }

    private android.graphics.drawable.GradientDrawable roundBackground(
        int color
    ) {
        android.graphics.drawable.GradientDrawable drawable =
            new android.graphics.drawable.GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(dp(14));

        if (color == card) {
            drawable.setStroke(dp(1), border);
        }

        return drawable;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams margins(
        int left,
        int top,
        int right,
        int bottom
    ) {
        LinearLayout.LayoutParams params = wrap();

        params.setMargins(
            dp(left),
            dp(top),
            dp(right),
            dp(bottom)
        );

        return params;
    }

    private LinearLayout.LayoutParams weight(
        float weight,
        int left,
        int top,
        int right,
        int bottom
    ) {
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
            );

        params.setMargins(
            dp(left),
            dp(top),
            dp(right),
            dp(bottom)
        );

        return params;
    }

    private int dp(int value) {
        return (int) (
            value * activity.getResources()
                .getDisplayMetrics()
                .density + 0.5f
        );
    }

    // =============================================================
    // Simple activity graph
    // =============================================================

    private static final class ActivityGraph extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int[] values;
        private final int color;
        private final int gridColor;

        ActivityGraph(
            Activity activity,
            int[] values,
            int color, int gridColor
        ) {
            super(activity);
            this.values = values;
            this.color = color;
            this.gridColor = gridColor;

            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();

            float left = dp(8);
            float right = width - dp(8);
            float top = dp(12);
            float bottom = height - dp(12);

            // Grid lines
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(gridColor);

            for (int i = 0; i < 4; i++) {
                float y = top + (bottom - top) * i / 3f;

                canvas.drawLine(
                    left,
                    y,
                    right,
                    y,
                    paint
                );
            }

            if (values.length < 2) {
                return;
            }

            int max = 1;

            for (int value : values) {
                if (value > max) {
                    max = value;
                }
            }

            path.reset();

            float step =
                (right - left) / (values.length - 1);

            for (int i = 0; i < values.length; i++) {
                float x = left + step * i;

                float normalized =
                    values[i] / (float) max;

                float y =
                    bottom -
                    normalized * (bottom - top);

                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }

            // Glow / thicker under-stroke
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setColor(Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)));

            canvas.drawPath(path, paint);

            // Main line
            paint.setStrokeWidth(dp(2));
            paint.setColor(color);

            canvas.drawPath(path, paint);

            // Points
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);

            for (int i = 0; i < values.length; i++) {
                float x = left + step * i;

                float normalized =
                    values[i] / (float) max;

                float y =
                    bottom -
                    normalized * (bottom - top);

                canvas.drawCircle(
                    x,
                    y,
                    dp(4),
                    paint
                );
            }
        }

        private int dp(int value) {
            return (int) (
                value * getResources()
                    .getDisplayMetrics()
                    .density + 0.5f
            );
        }
    }
}
