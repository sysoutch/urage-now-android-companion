package com.uragestudio.companion;

import android.content.Context;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** Isolates local Android speech and dashboard-generated speech playback from Chat UI state. */
final class ChatSpeechPlayer implements AutoCloseable {
    private final Context context;
    private TextToSpeech builtIn;
    private MediaPlayer generatedAudio;

    ChatSpeechPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    void speakBuiltIn(String text) {
        stop();
        builtIn = new TextToSpeech(context, result -> {
            if (result == TextToSpeech.SUCCESS && builtIn != null) {
                builtIn.setLanguage(Locale.getDefault());
                builtIn.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat-reply");
            }
        });
    }

    void playGeneratedAudio(String dataUrl) throws Exception {
        stopGeneratedAudio();
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("Dashboard TTS returned invalid audio.");
        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        File output = File.createTempFile("chat-tts-", ".audio", context.getCacheDir());
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(bytes);
        }
        generatedAudio = new MediaPlayer();
        generatedAudio.setDataSource(output.getAbsolutePath());
        generatedAudio.setOnCompletionListener(player -> {
            player.release();
            if (generatedAudio == player) generatedAudio = null;
            output.delete();
        });
        generatedAudio.prepare();
        generatedAudio.start();
    }

    void stop() {
        if (builtIn != null) builtIn.stop();
        stopGeneratedAudio();
    }

    private void stopGeneratedAudio() {
        if (generatedAudio == null) return;
        try { generatedAudio.stop(); } catch (Exception ignored) {}
        generatedAudio.release();
        generatedAudio = null;
    }

    @Override public void close() {
        stop();
        if (builtIn != null) builtIn.shutdown();
        builtIn = null;
    }
}
