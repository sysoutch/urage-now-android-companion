package com.uragestudio.companion;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Owns responsive workspace navigation: a left rail in landscape and bottom bar in portrait. */
final class WorkspaceRailController {
    private record Destination(String id, String label, int icon, int color) {}

    private final MobileUiKit ui;
    private final Consumer<String> navigation;
    private final boolean bottomNavigation;
    private final View view;
    private final LinearLayout items;
    private final Map<String, LinearLayout> itemViews = new LinkedHashMap<>();
    private final Map<String, TextView> badges = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();

    WorkspaceRailController(android.app.Activity activity, Consumer<String> navigation, boolean bottomNavigation) {
        this.navigation = navigation;
        this.bottomNavigation = bottomNavigation;
        ui = new MobileUiKit(activity);
        items = new LinearLayout(activity);
        items.setOrientation(bottomNavigation ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        items.setGravity(bottomNavigation ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        items.setPadding(ui.dp(6), bottomNavigation ? ui.dp(2) : ui.dp(8), ui.dp(6), bottomNavigation ? ui.dp(2) : ui.dp(8));

        if (!bottomNavigation) {
            TextView brand = new TextView(activity);
            brand.setText("LazyDev");
            brand.setTextColor(ui.accentStrongColor());
            brand.setTextSize(13);
            brand.setGravity(Gravity.CENTER);
            brand.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            items.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(46)));
        }

        for (Destination destination : destinations()) {
            items.addView(buildItem(activity, destination), itemLayout());
        }
        if (bottomNavigation) {
            HorizontalScrollView horizontal = new HorizontalScrollView(activity);
            horizontal.setHorizontalScrollBarEnabled(false);
            horizontal.setFillViewport(true);
            horizontal.setBackgroundColor(ui.surfaceColor());
            horizontal.addView(items, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, HorizontalScrollView.LayoutParams.MATCH_PARENT));
            view = horizontal;
        } else {
            ScrollView vertical = new ScrollView(activity);
            vertical.setVerticalScrollBarEnabled(false);
            vertical.setFillViewport(true);
            vertical.setBackgroundColor(ui.surfaceColor());
            vertical.addView(items);
            view = vertical;
        }
    }

    View view() { return view; }

    void select(String workspace) {
        if (!itemViews.containsKey(workspace)) return;
        for (Map.Entry<String, LinearLayout> entry : itemViews.entrySet()) {
            boolean active = entry.getKey().equals(workspace);
            entry.getValue().setBackground(ui.navigationDestinationBackground(active));
            entry.getValue().setSelected(active);
        }
        navigation.accept(workspace);
    }

    void updateJobCounts(Map<String, Integer> counts) {
        for (Map.Entry<String, TextView> entry : badges.entrySet()) {
            int count = Math.max(0, counts.getOrDefault(entry.getKey(), 0));
            entry.getValue().setText(count > 99 ? "99+" : Integer.toString(count));
            entry.getValue().setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            itemViews.get(entry.getKey()).setContentDescription(
                "Open " + labels.get(entry.getKey()) + (count > 0 ? ", " + count + " active jobs" : ""));
        }
    }

    private View buildItem(android.app.Activity activity, Destination destination) {
        FrameLayout wrapper = new FrameLayout(activity);
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(4));
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription("Open " + destination.label());
        ImageView icon = new ImageView(activity);
        icon.setImageResource(destination.icon());
        icon.setImageTintList(ColorStateList.valueOf(destination.color()));
        item.addView(icon, new LinearLayout.LayoutParams(ui.dp(21), ui.dp(21)));
        TextView label = new TextView(activity);
        label.setText(destination.label());
        label.setTextColor(ui.textColor());
        label.setTextSize(9);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLayout.topMargin = ui.dp(2);
        item.addView(label, labelLayout);
        item.setOnClickListener(ignored -> select(destination.id()));
        item.setBackground(ui.navigationDestinationBackground(false));
        wrapper.addView(item, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(activity);
        badge.setTextColor(ui.backgroundColor());
        badge.setTextSize(9);
        badge.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(ui.dp(18));
        badge.setMinHeight(ui.dp(18));
        badge.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        badge.setBackground(ui.selectedControlBackground());
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLayout = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, ui.dp(18), Gravity.TOP | Gravity.END);
        wrapper.addView(badge, badgeLayout);
        itemViews.put(destination.id(), item);
        labels.put(destination.id(), destination.label());
        if (isJobDestination(destination.id())) badges.put(destination.id(), badge);
        return wrapper;
    }

    private java.util.List<Destination> destinations() {
        return java.util.List.of(
            new Destination("home", "Home", R.drawable.ic_home, ui.accentStrongColor()),
            new Destination("gallery", "Gallery", R.drawable.ic_gallery, ui.textMutedColor()),
            new Destination("chat", "Chat", R.drawable.ic_chat, ui.accentStrongColor()),
            new Destination("image", "Image", R.drawable.ic_image, Color.parseColor("#65E19F")),
            new Destination("model3d", "3D", R.drawable.ic_cube, ui.accentStrongColor()),
            new Destination("audio", "Audio", R.drawable.ic_audio, Color.parseColor("#FFBD6A")),
            new Destination("music", "Music", R.drawable.ic_music, Color.parseColor("#FF80C4")),
            new Destination("video", "Video", R.drawable.ic_video, ui.accentColor()),
            new Destination("tools", "Tools", R.drawable.ic_create, ui.accentStrongColor()),
            new Destination("connection", "Connect", R.drawable.ic_connection, ui.textMutedColor())
        );
    }

    private LinearLayout.LayoutParams itemLayout() {
        if (bottomNavigation) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(68), LinearLayout.LayoutParams.MATCH_PARENT);
            params.setMargins(ui.dp(2), 0, ui.dp(2), 0);
            return params;
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(54));
        params.setMargins(0, ui.dp(2), 0, ui.dp(2));
        return params;
    }

    private boolean isJobDestination(String id) {
        return "image".equals(id) || "model3d".equals(id) || "audio".equals(id)
            || "music".equals(id) || "video".equals(id);
    }
}
