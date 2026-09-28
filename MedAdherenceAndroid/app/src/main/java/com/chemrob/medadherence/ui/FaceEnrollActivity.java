package com.chemrob.medadherence.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
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

import com.chemrob.medadherence.core.FaceSignature;
import com.chemrob.medadherence.core.FrameObs;
import com.google.mlkit.vision.face.Face;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Face scan for the patient profile. On-device face detection guides the patient (one face, looking
 * straight at the camera, eyes open, close enough, enough light) and captures automatically once
 * the face has been steady for a moment. Returns the photo path and the averaged face signature.
 */
@SuppressWarnings("deprecation")
public class FaceEnrollActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_PATH = "path", EXTRA_SIGNATURE = "signature";
    private static final String TAG = "MedAdherence";
    private static final int REQ_CAMERA = 31, STEADY_FRAMES = 8;

    private Camera camera;
    private int orientation, w, h;
    private SurfaceHolder holder;
    private TextView hint;
    private Vision vision;
    private final List<double[]> good = new ArrayList<>();
    private boolean done;

    public static Intent intent(Context c) { return new Intent(c, FaceEnrollActivity.class); }

    public static boolean hasFrontCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout top = Ui.vbox(this);
        top.setBackgroundColor(Color.argb(160, 0, 0, 0));
        int p = Ui.dp(this, 20);
        top.setPadding(p, Ui.dp(this, 40), p, p);
        Ui.text(top, "Face scan", 26, Color.WHITE, true);
        Ui.text(top, "Used to check it is you taking observed doses. It stays on this phone.", 15, Color.parseColor("#D0D3FF"), false);
        hint = Ui.text(top, "Starting camera...", 22, Color.WHITE, true);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = Ui.vbox(this);
        bottom.setPadding(p, p, p, Ui.dp(this, 36));
        Ui.button(bottom, "Cancel", Ui.SURFACE_VARIANT, v -> { setResult(RESULT_CANCELED); finish(); });
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        vision = new Vision(false);
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
        } else hint.setText("Camera permission is needed for the face scan.");
    }

    @Override public void surfaceCreated(SurfaceHolder hd) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open();
    }
    @Override public void surfaceChanged(SurfaceHolder hd, int f, int ww, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder hd) { release(); }

    private void open() {
        if (camera != null || done) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break; }
            }
            Camera.getCameraInfo(id, info);
            camera = Camera.open(id);
            orientation = info.orientation;
            camera.setDisplayOrientation(info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - info.orientation % 360) % 360 : info.orientation);
            Camera.Parameters params = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPreviewSizes())
                if (best == null || Math.abs(s.width * s.height - 640 * 480) < Math.abs(best.width * best.height - 640 * 480)) best = s;
            if (best != null) params.setPreviewSize(best.width, best.height);
            params.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(params);
            w = camera.getParameters().getPreviewSize().width;
            h = camera.getParameters().getPreviewSize().height;
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            hint.setText("Look straight at the camera");
        } catch (Exception e) {
            Log.e(TAG, "Camera failed", e);
            hint.setText("The camera could not be opened.");
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (done || data == null || vision.busy()) return;
        vision.analyze(data, w, h, orientation, System.currentTimeMillis(), (obs, face) -> onObs(data, obs, face));
    }

    private void onObs(byte[] frame, FrameObs o, Face face) {
        if (done) return;
        // Face box is in upright image space: width is the short camera side.
        int uprightW = orientation % 180 == 0 ? w : h;
        String problem = null;
        if (o.brightness < 0.15) problem = "Find more light";
        else if (o.faces == 0 || face == null) problem = "Put your face in the frame";
        else if (o.faces > 1) problem = "Only one person, please";
        else if (face.getBoundingBox().width() < uprightW * 0.35) problem = "Move the phone closer";
        else if (Math.abs(o.yaw) > 12 || Math.abs(o.pitch) > 12) problem = "Look straight at the camera";
        else if (!Double.isNaN(o.eyesOpen) && o.eyesOpen < 0.6) problem = "Keep your eyes open";
        else if (o.signature == null) problem = "Hold still";

        if (problem != null) {
            good.clear();
            hint.setText(problem);
            return;
        }
        good.add(o.signature);
        hint.setText("Hold still... " + Math.max(0, STEADY_FRAMES - good.size()));
        if (good.size() >= STEADY_FRAMES) finishScan(frame, face.getBoundingBox());
    }

    private void finishScan(byte[] frame, Rect faceBox) {
        done = true;
        double[] sig = FaceSignature.average(good);
        String path = savePortrait(frame, faceBox);
        release();
        if (sig == null || path == null) {
            hint.setText("Could not save the scan. Please try again.");
            done = false;
            good.clear();
            return;
        }
        hint.setText("Face saved");
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, path).putExtra(EXTRA_SIGNATURE, sig));
        getWindow().getDecorView().postDelayed(this::finish, 700);
    }

    /** Upright, cropped head-and-shoulders photo for the profile and the pharmacist's review. */
    private String savePortrait(byte[] frame, Rect box) {
        try {
            YuvImage yuv = new YuvImage(frame, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 90, raw);
            Bitmap bmp = BitmapFactory.decodeByteArray(raw.toByteArray(), 0, raw.size());
            Matrix m = new Matrix();
            m.postRotate(orientation);
            Bitmap up = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            int pad = box.width() / 2;
            int l = Math.max(0, box.left - pad), t = Math.max(0, box.top - pad);
            int r = Math.min(up.getWidth(), box.right + pad), btm = Math.min(up.getHeight(), box.bottom + pad);
            Bitmap crop = r > l && btm > t ? Bitmap.createBitmap(up, l, t, r - l, btm - t) : up;
            File out = new File(getFilesDir(), "profile_face_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                crop.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Could not save face photo", e);
            return null;
        }
    }

    private void release() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    @Override
    protected void onDestroy() {
        release();
        vision.close();
        super.onDestroy();
    }
}
