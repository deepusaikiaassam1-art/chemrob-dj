package com.chemrob.medadherence.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Takes a photo of a medicine (pack, strip or tablet) with the back camera and saves it in app
 * storage. Returns the file path as {@link #EXTRA_PATH}. Uses the platform camera API directly, so
 * no FileProvider or support library is needed.
 */
@SuppressWarnings("deprecation")
public class PhotoActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_PATH = "path";
    private static final String TAG = "MedAdherence";
    private static final int REQ_CAMERA = 11;
    private static final int MAX_SIDE = 1280;

    private Camera camera;
    private int orientation;
    private SurfaceHolder holder;
    private TextView hint;
    private boolean busy;

    public static Intent intent(Context c) { return new Intent(c, PhotoActivity.class); }

    /** Where medicine photos live. */
    public static File photoDir(Context c) {
        File d = new File(c.getFilesDir(), "med_photos");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Saves a bitmap as a new photo file (downscaled, JPEG) and returns its path, or null. */
    public static String save(Context c, Bitmap bmp) {
        if (bmp == null) return null;
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
        File out = new File(photoDir(c), "med_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Could not save photo", e);
            return null;
        }
    }

    /** Copies a picture chosen from the gallery into app storage. */
    public static String importImage(Context c, android.net.Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            return save(c, bmp);
        } catch (Exception e) {
            Log.w(TAG, "Could not import image", e);
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout top = Ui.vbox(this);
        top.setBackgroundColor(Color.argb(150, 0, 0, 0));
        int p = Ui.dp(this, 20);
        top.setPadding(p, Ui.dp(this, 40), p, p);
        hint = Ui.text(top, "Fit the medicine pack or tablet inside the frame", 20, Color.WHITE, true);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = Ui.hbox(this);
        bottom.setPadding(p, p, p, Ui.dp(this, 36));
        bottom.setBackgroundColor(Color.argb(150, 0, 0, 0));
        Ui.button(bottom, "Cancel", Ui.SURFACE_VARIANT, v -> { setResult(RESULT_CANCELED); finish(); });
        Ui.button(bottom, "Take photo", Ui.PRIMARY, v -> capture());
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        holder = surface.getHolder();
        holder.addCallback(this);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (holder.getSurface() != null && holder.getSurface().isValid()) open();
        } else {
            hint.setText("Camera permission is needed to photograph the medicine.");
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { release(); }

    private void open() {
        if (camera != null) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            Camera.getCameraInfo(id, info);
            camera = Camera.open(id);
            orientation = info.orientation;
            camera.setDisplayOrientation(info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - info.orientation % 360) % 360 : info.orientation);
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPictureSizes())
                if (best == null || Math.abs(s.width - 1600) < Math.abs(best.width - 1600)) best = s;
            if (best != null) params.setPictureSize(best.width, best.height);
            camera.setParameters(params);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (Exception e) {
            Log.e(TAG, "Camera failed", e);
            hint.setText("The camera could not be opened.");
        }
    }

    private void capture() {
        if (camera == null || busy) return;
        busy = true;
        hint.setText("Saving...");
        camera.takePicture(null, null, (data, cam) -> {
            Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length);
            String path = null;
            if (raw != null) {
                Matrix m = new Matrix();
                m.postRotate(orientation);
                path = save(this, Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true));
            }
            if (path == null) {
                busy = false;
                hint.setText("Could not save the photo. Try again.");
                cam.startPreview();
                return;
            }
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, path));
            finish();
        });
    }

    private void release() {
        if (camera == null) return;
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }
}
