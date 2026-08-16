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
        LinearLayout root = new LinearLayout(support.activity);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = new LinearLayout(support.activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button sfxTab = support.button("SFX");
        Button ttsTab = support.button("TTS");
        Button sttTab = support.button("STT");
        Button stsTab = support.button("STS");
        tabs.addView(sfxTab, weighted());
        tabs.addView(ttsTab, weighted());
        tabs.addView(sttTab, weighted());
        tabs.addView(stsTab, weighted());
        root.addView(tabs, support.layout());
        LinearLayout panel = support.panel("Audio Studio", "Generate sound effects, ambience, and prompt-driven audio.");
        EditText prompt = support.input("Describe the sound to generate");
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP);
        panel.addView(support.ui.field("Sound brief", "Describe the source, environment, timing, and character.", prompt), support.layout());
        Spinner duration = support.choice(List.of("5 seconds", "10 seconds", "20 seconds", "30 seconds", "60 seconds"), 1);
        panel.addView(support.ui.overline("Duration"), support.layout());
        panel.addView(duration, support.layout());
        Spinner steps = support.choice(List.of("25 steps", "50 steps", "75 steps", "100 steps"), 1);
        Spinner cfg = support.choice(List.of("CFG 3.0", "CFG 4.98", "CFG 6.0", "CFG 8.0"), 1);
        LinearLayout samplerRow = new LinearLayout(support.activity);
        samplerRow.setOrientation(LinearLayout.HORIZONTAL);
        samplerRow.addView(support.ui.field("Steps", "Stable Audio sampler iterations.", steps), weighted());
        samplerRow.addView(support.ui.field("CFG", "Prompt guidance strength.", cfg), weighted());
        panel.addView(samplerRow, support.layout());
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
                int[] stepValues = {25, 50, 75, 100};
                double[] cfgValues = {3.0, 4.98, 6.0, 8.0};
                support.queue("audio", new JSONObject().put("prompt", value)
                    .put("seconds", seconds[duration.getSelectedItemPosition()])
                    .put("steps", stepValues[steps.getSelectedItemPosition()])
                    .put("cfg", cfgValues[cfg.getSelectedItemPosition()]), result, "Audio");
            } catch (Exception error) {
                support.status.accept(support.message(error, "Could not queue audio generation."));
            }
        });
        panel.addView(generate, support.layout());
        panel.addView(support.jobsButton(), support.layout());

        LinearLayout ttsPanel = buildAudioModePanel("Text To Speech", "Use the dashboard's configured TTS workflow. Chat Studio settings choose built-in or ComfyUI playback.");
        LinearLayout sttPanel = buildAudioModePanel("Speech To Text", "Transcribe an audio attachment with the dashboard STT workflow. Attach audio from Chat Studio for now.");
        LinearLayout stsPanel = buildAudioModePanel("Speech To Speech", "Transform an audio attachment with the dashboard STS workflow. Attach audio from Chat Studio for now.");
        root.addView(panel, support.layout());
        root.addView(ttsPanel, support.layout());
        root.addView(sttPanel, support.layout());
        root.addView(stsPanel, support.layout());
        ttsPanel.setVisibility(View.GONE);
        sttPanel.setVisibility(View.GONE);
        stsPanel.setVisibility(View.GONE);
        View.OnClickListener selectSfx = ignored -> selectAudioPanel(panel, ttsPanel, sttPanel, stsPanel);
        View.OnClickListener selectTts = ignored -> selectAudioPanel(ttsPanel, panel, sttPanel, stsPanel);
        View.OnClickListener selectStt = ignored -> selectAudioPanel(sttPanel, panel, ttsPanel, stsPanel);
        View.OnClickListener selectSts = ignored -> selectAudioPanel(stsPanel, panel, ttsPanel, sttPanel);
        sfxTab.setOnClickListener(selectSfx);
        ttsTab.setOnClickListener(selectTts);
        sttTab.setOnClickListener(selectStt);
        stsTab.setOnClickListener(selectSts);
        return root;
    }

    private LinearLayout buildAudioModePanel(String title, String description) {
        LinearLayout panel = support.panel(title, description);
        panel.addView(support.result("Audio source picking will be shared with Chat Studio in the next companion workflow update."), support.layout());
        return panel;
    }

    private void selectAudioPanel(View selected, View... hidden) {
        selected.setVisibility(View.VISIBLE);
        for (View panel : hidden) panel.setVisibility(View.GONE);
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout buildMusic() {
        LinearLayout panel = support.panel("Music Studio", "Compose instrumental or vocal music from tags and optional lyrics.");
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
