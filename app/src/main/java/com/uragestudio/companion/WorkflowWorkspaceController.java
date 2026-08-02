package com.uragestudio.companion;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Composes Chat and focused media Studio controllers and owns workspace routing only. */
final class WorkflowWorkspaceController {
    private final ChatWorkspaceController chat;
    private final MediaStudioSupport support;
    private final ImageStudioController image;
    private final AudioMusicStudioController audioMusic;
    private final VideoStudioController video;
    private final Model3dStudioController model3d;
    private final CameraCaptureController camera;

    WorkflowWorkspaceController(
        Activity activity, ExecutorService executor, Handler main,
        Supplier<DashboardApi> dashboardApi, Supplier<MatrixSdkRelayClient> matrixRelay,
        Supplier<String> route, Consumer<String> status, Consumer<Exception> errors,
        Runnable refreshGallery
    ) {
        chat = new ChatWorkspaceController(activity, executor, main, dashboardApi, matrixRelay, route, status);
        camera = new CameraCaptureController(activity);
        support = new MediaStudioSupport(activity, executor, main, dashboardApi, matrixRelay, route, status, errors, refreshGallery, camera);
        image = new ImageStudioController(support);
        audioMusic = new AudioMusicStudioController(support);
        video = new VideoStudioController(support);
        model3d = new Model3dStudioController(support);
    }

    View chatView() { return chat.view(); }
    View imageView() { return image.view(); }
    View audioView() { return audioMusic.audioView(); }
    View musicView() { return audioMusic.musicView(); }
    View videoView() { return video.view(); }
    View model3dView() { return model3d.view(); }

    void show(String workspace) {
        chat.show("chat".equals(workspace));
        image.show("image".equals(workspace));
        audioMusic.showAudio("audio".equals(workspace));
        audioMusic.showMusic("music".equals(workspace));
        video.show("video".equals(workspace));
        model3d.show("model3d".equals(workspace));
    }

    void close() {
        support.close();
    }

    boolean handleActivityResult(int requestCode, int resultCode) {
        return camera.handleActivityResult(requestCode, resultCode);
    }
}
