package com.uragestudio.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Reusable preset picker/editor shared by every media Studio. */
final class PromptPresetUi {
    private final Activity activity;
    private final MobileUiKit ui;
    private final PromptPresetStore store;
    private final Consumer<String> status;

    PromptPresetUi(Activity activity, MobileUiKit ui, Consumer<String> status) {
        this.activity = activity;
        this.ui = ui;
        this.status = status;
        store = new PromptPresetStore(activity);
    }

    LinearLayout create(String studio, Callable<JSONObject> capture, Consumer<JSONObject> apply) {
        LinearLayout foldout = new LinearLayout(activity);
        foldout.setOrientation(LinearLayout.VERTICAL);

        Button header = ui.button("Prompt presets  ▸", MobileUiKit.ActionStyle.SECONDARY);
        header.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        header.setContentDescription("Expand prompt presets");

        LinearLayout panel = ui.cardContent();
        panel.setVisibility(android.view.View.GONE);

        panel.addView(ui.overline("Prompt presets"));
        panel.addView(ui.body(
            "Save complete Studio configurations, favorite the useful ones, and reuse them later."
        ));

        Spinner picker = new Spinner(activity);
        picker.setMinimumHeight(dp(52));
        picker.setBackground(ui.controlBackground());

        List<PromptPresetStore.Preset> presets = new ArrayList<>();
        StyledSpinnerAdapter<String> adapter =
            new StyledSpinnerAdapter<>(activity, new ArrayList<>());

        picker.setAdapter(adapter);

        Runnable refresh = () -> {
            presets.clear();
            presets.addAll(store.list(studio));

            adapter.clear();
            adapter.add("Choose a preset");

            for (PromptPresetStore.Preset preset : presets) {
                adapter.add((preset.favorite() ? "★ " : "") + preset.name());
            }

            adapter.notifyDataSetChanged();
            picker.setSelection(0);
        };

        refresh.run();

        picker.setOnItemSelectedListener(
            new SimpleItemSelection(position -> {
                if (position > 0 && position <= presets.size()) {
                    apply.accept(presets.get(position - 1).values());
                }
            })
        );

        panel.addView(picker, ui.spacedMatchWrap());

        LinearLayout actions = new LinearLayout(activity);

        Button save = ui.button("💾", MobileUiKit.ActionStyle.PRIMARY);
        save.setContentDescription("Save current");
        save.setTooltipText("Save current");
        save.setOnClickListener(view ->
            promptForName(studio, capture, refresh)
        );

        Button favorite = ui.button("♡", MobileUiKit.ActionStyle.SECONDARY);
        favorite.setContentDescription("Favorite");
        favorite.setTooltipText("Favorite");
        favorite.setOnClickListener(view -> {
            int position = picker.getSelectedItemPosition();

            if (position <= 0 || position > presets.size()) {
                status.accept("Choose a preset to favorite.");
                return;
            }

            store.toggleFavorite(studio, presets.get(position - 1).id());
            refresh.run();
            status.accept("Preset favorite updated.");
        });

        Button delete = ui.button("✕", MobileUiKit.ActionStyle.DANGER);
        delete.setContentDescription("Delete");
        delete.setTooltipText("Delete");
        delete.setOnClickListener(view -> {
            int position = picker.getSelectedItemPosition();

            if (position <= 0 || position > presets.size()) {
                status.accept("Choose a preset to delete.");
                return;
            }

            store.delete(studio, presets.get(position - 1).id());
            refresh.run();
            status.accept("Preset deleted.");
        });

        actions.addView(save, weighted());
        actions.addView(favorite, weighted());
        actions.addView(delete, weighted());

        panel.addView(actions, ui.spacedMatchWrap());

        header.setOnClickListener(view -> {
            boolean expanded =
                panel.getVisibility() == android.view.View.VISIBLE;

            panel.setVisibility(
                expanded
                    ? android.view.View.GONE
                    : android.view.View.VISIBLE
            );

            header.setText(
                expanded
                    ? "Prompt presets  ▸"
                    : "Prompt presets  ▾"
            );

            header.setContentDescription(
                expanded
                    ? "Expand prompt presets"
                    : "Collapse prompt presets"
            );
        });

        foldout.addView(header, ui.spacedMatchWrap());
        foldout.addView(panel, ui.spacedMatchWrap());

        return foldout;
    }

    private void promptForName(String studio, Callable<JSONObject> capture, Runnable refresh) {
        EditText name = ui.input("Preset name");
        new AlertDialog.Builder(activity)
            .setTitle("Save prompt preset")
            .setView(name)
            .setPositiveButton("Save", (dialog, which) -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) {
                    status.accept("Preset name is required.");
                    return;
                }
                try {
                    store.save(studio, value, capture.call());
                } catch (Exception error) {
                    status.accept("Could not save this preset: " + error.getMessage());
                    return;
                }
                refresh.run();
                status.accept("Saved preset “" + value + "”.");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
