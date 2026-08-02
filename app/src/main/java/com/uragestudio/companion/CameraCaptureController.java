package com.uragestudio.companion;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.function.Consumer;

/** Owns front/back camera intent setup and returns a durable captured-image URI. */
final class CameraCaptureController {
    private static final int CAPTURE_IMAGE = 7310;
    private final Activity activity;
    private Uri pendingUri;
    private Consumer<Uri> pendingResult;

    CameraCaptureController(Activity activity) {
        this.activity = activity;
    }

    void capture(boolean frontFacing, Consumer<Uri> result) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "urage-capture-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/URage NOW");
        pendingUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (pendingUri == null) throw new IllegalStateException("Android could not create a camera destination.");
        pendingResult = result;
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, pendingUri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int facing = frontFacing ? 1 : 0;
        intent.putExtra("android.intent.extras.CAMERA_FACING", facing);
        intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", frontFacing);
        activity.startActivityForResult(intent, CAPTURE_IMAGE);
    }

    boolean handleActivityResult(int requestCode, int resultCode) {
        if (requestCode != CAPTURE_IMAGE) return false;
        Uri captured = pendingUri;
        Consumer<Uri> callback = pendingResult;
        pendingUri = null;
        pendingResult = null;
        if (resultCode == Activity.RESULT_OK && captured != null && callback != null) {
            callback.accept(captured);
        } else if (captured != null) {
            activity.getContentResolver().delete(captured, null, null);
        }
        return true;
    }
}
