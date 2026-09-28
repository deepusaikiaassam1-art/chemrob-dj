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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.core.FaceMatch;
import com.chemrob.medadherence.core.FaceSignature;
import com.chemrob.medadherence.core.FrameObs;
import com.google.mlkit.vision.face.Face;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Face enrolment for the patient profile, guided like a phone's face unlock: look straight, turn
 * the head a little to each side, blink, and look straight again. On-device face detection checks
 * each pose and the recognition model (see {@link FaceRecognizer}) records a face fingerprint from
 * several angles, so the patient is recognised later even when not facing the camera squarely.
 * Returns the photo path, the fingerprints and the older geometry signature.
 */
@SuppressWarnings("deprecation")
public class FaceEnrollActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_PATH = "path", EXTRA_SIGNATURE = "signature", EXTRA_EMBEDDINGS = "embeddings";
    private static final String TAG = "MedAdherence";
    private static final int REQ_CAMERA = 31, STEADY_FRAMES = 3;
    private static final long TURN_TIMEOUT_MS = 20000, BLINK_TIMEOUT_MS = 15000;

    /** Enrolment stages, in order. */
    private enum Stage {
        STRAIGHT("Look straight at the camera", 2),
        TURN_A("Slowly turn your head a little to one side", 1),
        TURN_B("Now turn a little to the other side", 1),
        BLINK("Now blink your eyes", 0),
        FINAL("Look straight at the camera again", 1);
        final String prompt;
        final int views; // face fingerprints recorded in this stage
        Stage(String prompt, int views) { this.prompt = prompt; this.views = views; }
    }

    private Camera camera;
    private int orientation, w, h;
    private SurfaceHolder holder;
    private TextView hint, progress;
    private LinearLayout dots;
    private Vision vision;
    private FaceRecognizer recognizer;
    private final List<double[]> signatures = new ArrayList<>();
    private final List<float[]> embeddings = new ArrayList<>();
    private int stage = -1, steady, stageViews, blinkState;
    private long stageStart, lastViewAt;
    private double turnSign;
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

    /** Unpacks the fingerprints returned in {@link #EXTRA_EMBEDDINGS}. */
    public static List<float[]> embeddings(Intent data) {
        List<float[]> out = new ArrayList<>();
        float[] flat = data == null ? null : data.getFloatArrayExtra(EXTRA_EMBEDDINGS);
        if (flat == null) return out;
        for (int i = 0; i + 128 <= flat.length; i += 128) out.add(java.util.Arrays.copyOfRange(flat, i, i + 128));
        return out;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout top = Ui.vbox(this);
        top.setBackgroundColor(Color.argb(170, 0, 0, 0));
        int p = Ui.dp(this, 20);
        top.setPadding(p, Ui.dp(this, 40), p, p);
        Ui.text(top, "Face scan", 26, Color.WHITE, true);
        Ui.text(top, "Like a phone's face unlock: the app learns your face from a few angles, so it can "
                + "recognise you during observed doses. It stays on this phone.", 15, Color.parseColor("#D0D3FF"), false);
        dots = new LinearLayout(this);
        dots.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        for (int i = 0; i < Stage.values().length; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1);
            lp.setMargins(0, 0, Ui.dp(this, 6), 0);
            dots.addView(d, lp);
        }
        top.addView(dots);
        progress = Ui.text(top, "", 14, Color.WHITE, false);
        hint = Ui.text(top, "Starting camera...", 22, Color.WHITE, true);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = Ui.vbox(this);
        bottom.setPadding(p, p, p, Ui.dp(this, 36));
        Ui.button(bottom, "Cancel", Ui.SURFACE_VARIANT, v -> { setResult(RESULT_CANCELED); finish(); });
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        vision = new Vision(false);
        recognizer = FaceRecognizer.get(this);
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
            restart(null);
        } catch (Exception e) {
            Log.e(TAG, "Camera failed", e);
            hint.setText("The camera could not be opened.");
        }
    }

    /** Starts the scan from the beginning, optionally explaining why. */
    private void restart(String why) {
        signatures.clear();
        embeddings.clear();
        lastFace = null;
        stage = -1;
        if (why != null) Voice.say(this, why);
        nextStage(why == null);
    }

    private void nextStage(boolean interrupt) {
        stage++;
        steady = 0;
        stageViews = 0;
        blinkState = 0;
        stageStart = System.currentTimeMillis();
        if (stage >= Stage.values().length) { finishScan(); return; }
        Stage s = Stage.values()[stage];
        hint.setText(s.prompt);
        progress.setText(String.format(java.util.Locale.ROOT, "Step %d of %d", stage + 1, Stage.values().length));
        for (int i = 0; i < dots.getChildCount(); i++)
            dots.getChildAt(i).setBackground(Ui.rounded(this, i < stage ? Ui.GOOD : i == stage ? Color.WHITE : Color.argb(90, 255, 255, 255), 3));
        if (interrupt) Voice.say(this, s.prompt);
        else Voice.then(this, s.prompt);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (done || stage < 0 || data == null || vision.busy()) return;
        vision.analyze(data, w, h, orientation, System.currentTimeMillis(), (obs, face) -> onObs(data, obs, face));
    }

    private void onObs(byte[] frame, FrameObs o, Face face) {
        if (done || stage < 0 || stage >= Stage.values().length) return;
        Stage s = Stage.values()[stage];
        long elapsed = System.currentTimeMillis() - stageStart;
        // Face box is in upright image space: width is the short camera side.
        int uprightW = orientation % 180 == 0 ? w : h;
        String problem = null;
        if (o.brightness < 0.15) problem = "Find more light";
        else if (o.faces == 0 || face == null) problem = "Put your face in the frame";
        else if (o.faces > 1) problem = "Only one person, please";
        else if (face.getBoundingBox().width() < uprightW * 0.35) problem = "Move the phone closer";
        if (problem != null) {
            steady = 0;
            hint.setText(problem);
            return;
        }

        boolean poseOk;
        switch (s) {
            case STRAIGHT:
            case FINAL:
                poseOk = Math.abs(o.yaw) <= 10 && Math.abs(o.pitch) <= 12 && (Double.isNaN(o.eyesOpen) || o.eyesOpen >= 0.6);
                if (!poseOk) hint.setText(Math.abs(o.yaw) > 10 || Math.abs(o.pitch) > 12 ? "Look straight at the camera" : "Keep your eyes open");
                break;
            case TURN_A:
                poseOk = Math.abs(o.yaw) >= 15 && Math.abs(o.yaw) <= 40;
                if (poseOk) turnSign = Math.signum(o.yaw);
                else hint.setText(Math.abs(o.yaw) > 40 ? "Not so far - turn back a little" : s.prompt);
                break;
            case TURN_B:
                poseOk = Math.signum(o.yaw) == -turnSign && Math.abs(o.yaw) >= 15 && Math.abs(o.yaw) <= 40;
                if (!poseOk) hint.setText(Math.abs(o.yaw) > 40 ? "Not so far - turn back a little" : s.prompt);
                break;
            default: // BLINK: eyes seen open, then closed, then open again
                if (!Double.isNaN(o.eyesOpen)) {
                    if (blinkState == 0 && o.eyesOpen > 0.6) blinkState = 1;
                    else if (blinkState == 1 && o.eyesOpen < 0.3) blinkState = 2;
                    else if (blinkState == 2 && o.eyesOpen > 0.6) blinkState = 3;
                }
                if (blinkState == 3) { Voice.say(this, "Good."); nextStage(false); }
                else if (elapsed > BLINK_TIMEOUT_MS) nextStage(false); // liveness is re-checked at every dose
                return;
        }
        if (!poseOk) {
            steady = 0;
            if ((s == Stage.TURN_A || s == Stage.TURN_B) && elapsed > TURN_TIMEOUT_MS) nextStage(false);
            return;
        }
        if (++steady < STEADY_FRAMES) { hint.setText("Hold still..."); return; }
        if (s == Stage.STRAIGHT || s == Stage.FINAL) {
            if (o.signature != null) signatures.add(o.signature);
            if (s == Stage.FINAL) lastFace = new Object[]{frame, face.getBoundingBox()};
        }
        capture(frame, face, s);
    }

    private Object[] lastFace; // frame and face box for the profile photo

    /** Records one face fingerprint for this stage (spaced out, so each view differs a little). */
    private void capture(byte[] frame, Face face, Stage s) {
        if (recognizer == null) { // no recognition model on this phone: geometry signature only
            if (++stageViews >= s.views) { Voice.say(this, "Good."); nextStage(false); }
            return;
        }
        if (recognizer.busy() || System.currentTimeMillis() - lastViewAt < 350) return;
        double[] pts = Vision.alignPoints(face);
        if (pts == null) return;
        lastViewAt = System.currentTimeMillis();
        final int at = stage;
        recognizer.embedAsync(frame, w, h, orientation, pts, e -> {
            if (done || at != stage || e == null) return;
            // Every view must look like the first one: a second person during the scan restarts it.
            if (!embeddings.isEmpty() && FaceMatch.best(embeddings, e) < FaceMatch.MIN_ENROL_CONSISTENCY) {
                restart("That did not look like the same face. Let's start again. Only you in the picture, please.");
                return;
            }
            embeddings.add(e);
            if (++stageViews >= s.views) { Voice.say(this, "Good."); nextStage(false); }
        });
    }

    private void finishScan() {
        done = true;
        double[] sig = FaceSignature.average(signatures);
        String path = lastFace == null ? null : savePortrait((byte[]) lastFace[0], (Rect) lastFace[1]);
        double quality = FaceMatch.consistency(embeddings);
        if (path == null || (recognizer != null && (embeddings.size() < 3 || quality < FaceMatch.MIN_ENROL_CONSISTENCY))) {
            done = false;
            lastFace = null;
            restart("The scan was not clear enough. Let's try again, in good light.");
            return;
        }
        release();
        hint.setText(recognizer != null ? "Face learned from " + embeddings.size() + " views" : "Face saved");
        progress.setText("");
        Voice.say(this, "Your face is saved. Thank you.");
        Intent result = new Intent().putExtra(EXTRA_PATH, path);
        if (sig != null) result.putExtra(EXTRA_SIGNATURE, sig);
        if (!embeddings.isEmpty()) {
            float[] flat = new float[embeddings.size() * 128];
            for (int i = 0; i < embeddings.size(); i++) System.arraycopy(embeddings.get(i), 0, flat, i * 128, 128);
            result.putExtra(EXTRA_EMBEDDINGS, flat);
        }
        setResult(RESULT_OK, result);
        getWindow().getDecorView().postDelayed(this::finish, 900);
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
