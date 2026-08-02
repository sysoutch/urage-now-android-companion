package com.uragestudio.companion;

import android.app.Activity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.google.android.material.card.MaterialCardView;
import java.util.List;
import java.util.function.Consumer;

/** Presents and applies the dashboard-compatible Studio theme selection. */
final class StudioThemeSectionController {
    private final Activity activity;
    private final StudioThemeStore store;
    private final MobileUiKit ui;
    private final MaterialCardView view;
    private Runnable synchronize = () -> {};

    StudioThemeSectionController(Activity activity, Consumer<String> status) {
        this.activity = activity;
        store = new StudioThemeStore(activity);
        ui = new MobileUiKit(activity);
        view = build(status);
    }

    MaterialCardView view() {
        return view;
    }

    void setSynchronizeAction(Runnable action) {
        synchronize = action == null ? () -> {} : action;
    }

    private MaterialCardView build(Consumer<String> status) {
        List<StudioThemeStore.Palette> palettes = store.palettes();
        LinearLayout content = ui.cardContent();
        content.addView(ui.overline("Appearance"));
        content.addView(ui.sectionTitle("Studio theme"));
        content.addView(ui.body("Use the same theme family across the desktop Studio and Android companion."));
        Spinner themes = new Spinner(activity);
        themes.setAdapter(new StyledSpinnerAdapter<>(activity, palettes.stream().map(StudioThemeStore.Palette::label).toList()));
        themes.setBackground(ui.controlBackground());
        themes.setMinimumHeight(ui.dp(52));
        int selected = 0;
        String activeId = store.active().id();
        for (int index = 0; index < palettes.size(); index++) {
            if (palettes.get(index).id().equals(activeId)) selected = index;
        }
        themes.setSelection(selected);
        CheckBox followDashboard = new CheckBox(activity);
        followDashboard.setText("Follow paired dashboard theme");
        followDashboard.setTextColor(ui.textColor());
        followDashboard.setChecked(store.usesDashboardTheme());
        themes.setEnabled(!followDashboard.isChecked());
        followDashboard.setOnCheckedChangeListener((ignored, checked) -> themes.setEnabled(!checked));
        content.addView(followDashboard, ui.spacedMatchWrap());
        content.addView(themes, ui.spacedMatchWrap());
        content.addView(ui.body("When following, the last synchronized dashboard theme remains available offline. Disable it to keep an Android-only override."));
        Button apply = ui.button("Apply theme preference", MobileUiKit.ActionStyle.PRIMARY);
        apply.setOnClickListener(ignored -> {
            String before = store.active().id();
            if (followDashboard.isChecked()) {
                store.useDashboardTheme();
                status.accept("Android now follows the paired dashboard theme.");
                if (!before.equals(store.active().id())) {
                    activity.recreate();
                    return;
                }
                synchronize.run();
                return;
            }
            StudioThemeStore.Palette palette = palettes.get(themes.getSelectedItemPosition());
            if (!store.usesDashboardTheme() && palette.id().equals(store.active().id())) {
                status.accept(palette.label() + " is already active.");
                return;
            }
            store.useLocalTheme(palette.id());
            status.accept("Applying local " + palette.label() + " override.");
            activity.recreate();
        });
        content.addView(apply, ui.spacedMatchWrap());
        MaterialCardView card = ui.card();
        card.addView(content);
        return card;
    }
}
