package com.uragestudio.companion;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import org.json.JSONObject;

final class AudioMusicStudioController {
    private final MediaStudioSupport support;
    private final ScrollView audioView;
    private final ScrollView musicView;

    AudioMusicStudioController(MediaStudioSupport support) {
        this.support = support;
        audioView = support.scroll(buildAudio());
        musicView = support.scroll(buildMusic());
    }

    View audioView() { return audioView; }
    View musicView() { return musicView; }
    void showAudio(boolean visible) { audioView.setVisibility(visible ? View.VISIBLE : View.GONE); }
    void showMusic(boolean visible) { musicView.setVisibility(visible ? View.VISIBLE : View.GONE); }

    private LinearLayout buildAudio() {
        LinearLayout panel = support.panel("Audio Studio", "Generate sound effects, ambience, and prompt-driven audio.");
        support.addStudioContext(panel);
        EditText prompt = support.input("Describe the sound to generate");
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP);
        panel.addView(support.ui.field("Sound brief", "Describe the source, environment, timing, and character.", prompt), support.layout());
        Spinner duration = support.choice(List.of("5 seconds", "10 seconds", "20 seconds", "30 seconds", "60 seconds"), 1);
        panel.addView(support.ui.overline("Duration"), support.layout());
        panel.addView(duration, support.layout());
        panel.addView(support.presets.create("audio", () -> new JSONObject()
            .put("prompt", prompt.getText().toString()).put("duration", duration.getSelectedItemPosition()), values -> {
                prompt.setText(values.optString("prompt"));
                duration.setSelection(Math.max(0, Math.min(values.optInt("duration", 1), 4)));
            }), support.layout());
        StudioWorkflowResultView result = support.workflowResult("Generated audio will appear here.");
        support.bindResult("audio", result);
        panel.addView(result, support.layout());
        Button generate = support.primaryButton("Generate audio");
        generate.setOnClickListener(ignored -> {
            String value = prompt.getText().toString().trim();
            if (value.isEmpty()) {
                support.status.accept("Enter an Audio Studio prompt first.");
                return;
            }
            try {
                int[] seconds = {5, 10, 20, 30, 60};
                support.queue("audio", new JSONObject().put("prompt", value)
                    .put("seconds", seconds[duration.getSelectedItemPosition()]), result, "Audio");
            } catch (Exception error) {
                support.status.accept(support.message(error, "Could not queue audio generation."));
            }
        });
        panel.addView(generate, support.layout());
        panel.addView(support.jobsButton(), support.layout());
        return panel;
    }

    private LinearLayout buildMusic() {
        LinearLayout panel = support.panel("Music Studio", "Compose instrumental or vocal music from tags and optional lyrics.");
        support.addStudioContext(panel);
        EditText tags = support.input("ambient, cinematic, piano, slow");
        panel.addView(support.ui.field("Music tags", "Comma-separated genre, mood, instrumentation, vocal, and production cues.", tags), support.layout());
        EditText lyrics = support.input("[verse]\n...\n\n[chorus]\n...");
        lyrics.setMinLines(6);
        lyrics.setGravity(Gravity.TOP);
        panel.addView(support.ui.field("Lyrics", "Optional. Use [verse], [chorus], [bridge], and [outro] section labels.", lyrics), support.layout());
        Spinner duration = support.choice(List.of("15 seconds", "30 seconds", "60 seconds", "120 seconds"), 1);
        panel.addView(support.ui.overline("Duration"), support.layout());
        panel.addView(duration, support.layout());
        panel.addView(support.presets.create("music", () -> new JSONObject()
            .put("tags", tags.getText().toString()).put("lyrics", lyrics.getText().toString())
            .put("duration", duration.getSelectedItemPosition()), values -> {
                tags.setText(values.optString("tags"));
                lyrics.setText(values.optString("lyrics"));
                duration.setSelection(Math.max(0, Math.min(values.optInt("duration", 1), 3)));
            }), support.layout());
        StudioWorkflowResultView result = support.workflowResult("Generated music will appear here.");
        support.bindResult("music", result);
        panel.addView(result, support.layout());
        Button generate = support.primaryButton("Generate music");
        generate.setOnClickListener(ignored -> {
            String tagValue = tags.getText().toString().trim();
            String lyricValue = lyrics.getText().toString().trim();
            if (tagValue.isEmpty() && lyricValue.isEmpty()) {
                support.status.accept("Enter music tags or lyrics first.");
                return;
            }
            try {
                int[] seconds = {15, 30, 60, 120};
                support.queue("music", new JSONObject().put("tags", tagValue).put("lyrics", lyricValue)
                    .put("seconds", seconds[duration.getSelectedItemPosition()]), result, "Music");
            } catch (Exception error) {
                support.status.accept(support.message(error, "Could not queue music generation."));
            }
        });
        panel.addView(generate, support.layout());
        panel.addView(support.jobsButton(), support.layout());
        return panel;
    }
}
