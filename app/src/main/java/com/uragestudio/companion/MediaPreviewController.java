package com.uragestudio.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.view.WindowManager;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class MediaPreviewController {
    private final Activity activity;
    private final ExecutorService executor;
    private final Handler main;
    private final MobileUiKit ui;
    private final MediaPreviewCache cache;
    private final Consumer<Exception> errors;

    MediaPreviewController(
        Activity activity, ExecutorService executor, Handler main,
        MediaPreviewCache cache, Consumer<Exception> errors
    ) {
        this.activity = activity;
        this.executor = executor;
        this.main = main;
        this.cache = cache;
        this.errors = errors;
        ui = new MobileUiKit(activity);
    }

    void show(MediaItem item) {
        ProgressBar loading = new ProgressBar(activity);
        LinearLayout loadingPanel = panel();
        loadingPanel.setGravity(Gravity.CENTER);
        loadingPanel.addView(loading);
        AlertDialog waiting = new AlertDialog.Builder(activity)
            .setTitle(title(item)).setView(loadingPanel).setNegativeButton("Cancel", null).show();
        executor.execute(() -> {
            try {
                File file = cache.resolve(item);
                float[] waveform = "audio".equals(item.kind()) ? waveform(file) : new float[0];
                main.post(() -> {
                    waiting.dismiss();
                    if ("image".equals(item.kind())) showImage(item, file);
                    else if ("video".equals(item.kind())) showVideo(item, file);
                    else if ("audio".equals(item.kind())) showAudio(item, file, waveform);
                    else if ("model3d".equals(item.kind())) showModel(item, file);
                    else showFile(item);
                });
            } catch (Exception error) {
                main.post(waiting::dismiss);
                errors.accept(error);
            }
        });
    }

    void bindModelPreview(MediaItem item, Model3dPreviewView preview) {
        executor.execute(() -> {
            try {
                File file = cache.resolve(item);
                main.post(() -> preview.showModel(file));
            } catch (Exception error) {
                main.post(() -> errors.accept(error));
            }
        });
    }

    private void showModel(MediaItem item, File file) {
        if (!Model3dPreviewView.supportsPreview(file)) {
            showUnsupportedModel(item, file);
            return;
        }
        Model3dPreviewView preview = new Model3dPreviewView(activity);
        int displayHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int previewHeight = Math.min(Math.round(displayHeight * 0.70f), ui.dp(560));
        preview.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, previewHeight
        ));
        preview.showModel(file);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(title(item))
            .setView(preview)
            .setPositiveButton("Close", null)
            .create();
        dialog.setOnDismissListener(ignored -> preview.destroy());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.controlBackground());
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void showUnsupportedModel(MediaItem item, File file) {
        new AlertDialog.Builder(activity)
            .setTitle(title(item))
            .setMessage(
                file.getName() + " cannot be previewed on this phone yet. "
                    + "GLB and FBX open in the interactive preview; download this file or long-press it in Gallery to use its available actions."
            )
            .setPositiveButton("Close", null)
            .show();
    }

    private void showImage(MediaItem item, File file) {
        ImageView image = new ImageView(activity);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(decodePreviewBitmap(file));
        new AlertDialog.Builder(activity).setTitle(title(item)).setView(image)
            .setPositiveButton("Close", null).show();
    }

    private void showVideo(MediaItem item, File file) {
        VideoView video = new VideoView(activity);
        MediaController controls = new MediaController(activity);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setVideoURI(Uri.fromFile(file));
        video.setOnPreparedListener(player -> {
            player.setLooping(false);
            video.start();
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title(item)).setView(video)
            .setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> video.stopPlayback());
        dialog.show();
    }

    private void showAudio(MediaItem item, File file, float[] waveform) {
        LinearLayout content = panel();
        AudioWaveformView waveformView = new AudioWaveformView(activity);
        waveformView.setAmplitudes(waveform);
        content.addView(waveformView, ui.matchWrap());
        SeekBar timeline = new SeekBar(activity);
        content.addView(timeline, ui.matchWrap());
        MaterialButton play = ui.button("Play", MobileUiKit.ActionStyle.PRIMARY);
        content.addView(play, ui.spacedMatchWrap());
        TextView detail = ui.body(item.fileName());
        content.addView(detail, ui.spacedMatchWrap());

        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            timeline.setMax(player.getDuration());
            detail.setText(item.fileName() + "  •  " + duration(player.getDuration()));
        } catch (Exception error) {
            player.release();
            errors.accept(error);
            return;
        }
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!player.isPlaying()) return;
                int position = player.getCurrentPosition();
                timeline.setProgress(position);
                waveformView.setProgress(position / (float) Math.max(1, player.getDuration()));
                main.postDelayed(this, 120);
            }
        };
        play.setOnClickListener(view -> {
            if (player.isPlaying()) {
                player.pause();
                play.setText("Play");
            } else {
                player.start();
                play.setText("Pause");
                main.post(update);
            }
        });
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    player.seekTo(progress);
                    waveformView.setProgress(progress / (float) Math.max(1, player.getDuration()));
                }
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        player.setOnCompletionListener(ignored -> {
            play.setText("Play");
            waveformView.setProgress(1);
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title(item)).setView(content)
            .setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacks(update);
            player.release();
        });
        dialog.show();
    }

    private void showFile(MediaItem item) {
        new AlertDialog.Builder(activity).setTitle(title(item))
            .setMessage("Inline preview is not available for " + item.fileName() + ". Long-press it to download.")
            .setPositiveButton("Close", null).show();
    }

    private float[] waveform(File file) {
        try {
            return new AudioWaveformExtractor().extract(file);
        } catch (Exception ignored) {
            // Playback remains useful when a device codec cannot decode waveform samples.
            return new float[0];
        }
    }

    private android.graphics.Bitmap decodePreviewBitmap(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int target = Math.max(activity.getResources().getDisplayMetrics().widthPixels,
            activity.getResources().getDisplayMetrics().heightPixels);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > target * 2
            || bounds.outHeight / options.inSampleSize > target * 2) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        return panel;
    }

    private String title(MediaItem item) {
        return item.title().isBlank() ? item.fileName() : item.title();
    }

    private String duration(int milliseconds) {
        int seconds = milliseconds / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
