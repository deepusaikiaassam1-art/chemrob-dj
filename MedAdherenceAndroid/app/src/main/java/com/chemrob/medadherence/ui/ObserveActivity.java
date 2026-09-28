package com.chemrob.medadherence.ui;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.alarm.AlarmReceiver;
import com.chemrob.medadherence.alarm.AlarmScheduler;
import com.chemrob.medadherence.alarm.Notifications;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseKey;
import com.chemrob.medadherence.core.DoseRecord;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.FrameAnalysis;
import com.chemrob.medadherence.core.Medication;
import com.chemrob.medadherence.core.ScheduleEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Observed-dose mode: the front camera watches the patient take the medicine.
 * Five guided steps; each is checked for light, a person in view and live movement, and a
 * photo is kept as evidence. All steps pass = auto-verified; otherwise the pharmacist reviews.
 * Uses the long-standing android.hardware.Camera API so no support libraries are needed.
 */
@SuppressWarnings("deprecation")
public class ObserveActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "MedAdherence";
    private static final String[] STEPS = {
            "Look at the camera so your face is in the frame",
            "Hold the medicine up to the camera",
            "Put the medicine in your mouth",
            "Drink water and swallow",
            "Open your mouth to show it is empty",
    };
    private static final long STEP_MS = 6000, SAMPLE_MS = 200;
    private static final int REQ_CAMERA = 7;

    private final FrameAnalysis.Criteria criteria = new FrameAnalysis.Criteria();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String key;
    private Medication med;
    private Camera camera;
    private int cameraOrientation;
    private int previewW, previewH;
    private SurfaceHolder holder;
    private TextView instruction, status, counter;

    // step state
    private int step = -1;
    private long stepStart, lastSample;
    private byte[] prevLuma, lastFrame;
    private double peakMotion, peakSkin, brightSum;
    private int samples, passed;
    private double livenessSum, presenceSum;
    private final List<String> evidence = new ArrayList<>();
    private boolean finished;

    public static Intent intent(Context ctx, String key) {
        Intent i = new Intent(ctx, ObserveActivity.class);
        i.setData(Uri.parse("medadherence://observe/" + Uri.encode(key)));
        i.putExtra(AlarmReceiver.EXTRA_KEY, key);
        return i;
    }

    public static PendingIntent pendingIntent(Context ctx, String key) {
        Intent i = intent(ctx, key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, ("observe" + key).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null) km.requestDismissKeyguard(this, null);

        key = getIntent().getStringExtra(AlarmReceiver.EXTRA_KEY);
        med = key == null ? null : Store.get(this).findMed(DoseKey.medId(key));
        if (med == null) { finish(); return; }
        Notifications.cancel(this, key); // stop the ringing while the patient is on camera

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout top = Ui.vbox(this);
        top.setBackgroundColor(Color.argb(170, 0, 0, 0));
        int p = Ui.dp(this, 20);
        top.setPadding(p, Ui.dp(this, 36), p, p);
        Ui.text(top, "Observed dose: " + med.name + " " + med.dose, 15, Color.parseColor("#9FE3D0"), true);
        counter = Ui.text(top, "", 14, Color.WHITE, false);
        instruction = Ui.text(top, "Starting camera...", 24, Color.WHITE, true);
        status = Ui.text(top, "", 14, Color.WHITE, false);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = Ui.vbox(this);
        bottom.setPadding(p, p, p, Ui.dp(this, 32));
        Ui.button(bottom, "Cancel", Ui.BAD, v -> cancel());
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
            if (holder.getSurface() != null && holder.getSurface().isValid()) openCamera();
        } else {
            instruction.setText("Camera permission is needed to observe this dose.");
            handler.postDelayed(this::cancel, 2500);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { releaseCamera(); }

    private void openCamera() {
        if (camera != null || finished) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break; }
            }
            Camera.getCameraInfo(id, info);
            camera = Camera.open(id);
            cameraOrientation = info.orientation;
            // Portrait-only activity: display rotation is 0.
            int display = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - info.orientation % 360) % 360 : info.orientation;
            camera.setDisplayOrientation(display);

            Camera.Parameters params = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPreviewSizes())
                if (best == null || Math.abs(s.width * s.height - 640 * 480) < Math.abs(best.width * best.height - 640 * 480)) best = s;
            if (best != null) params.setPreviewSize(best.width, best.height);
            params.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(params);
            previewW = camera.getParameters().getPreviewSize().width;
            previewH = camera.getParameters().getPreviewSize().height;
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            if (step >= 0) { // came back after leaving the screen: restart the current step
                handler.removeCallbacksAndMessages(null);
                step--;
            }
            nextStep();
        } catch (Exception e) {
            Log.e(TAG, "Camera failed", e);
            instruction.setText("The camera could not be opened.");
            handler.postDelayed(this::cancel, 2500);
        }
    }

    private void releaseCamera() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    private void nextStep() {
        step++;
        if (step >= STEPS.length) { complete(); return; }
        counter.setText(String.format(Locale.ROOT, "Step %d of %d", step + 1, STEPS.length));
        instruction.setText(STEPS[step]);
        stepStart = System.currentTimeMillis();
        prevLuma = null;
        peakMotion = peakSkin = brightSum = 0;
        samples = 0;
        handler.postDelayed(this::endStep, STEP_MS);
        handler.post(this::tick);
    }

    private void tick() {
        if (finished || step < 0 || step >= STEPS.length) return;
        long left = STEP_MS - (System.currentTimeMillis() - stepStart);
        status.setText(String.format(Locale.ROOT, "%s person in view    %s movement    %s light      %ds",
                mark(peakSkin >= criteria.minSkin), mark(peakMotion >= criteria.minMotion),
                mark(samples > 0 && brightSum / samples >= criteria.minBrightness), Math.max(0, (left + 999) / 1000)));
        if (left > 0) handler.postDelayed(this::tick, 250);
    }

    private static String mark(boolean ok) { return ok ? "[OK]" : "[ .. ]"; }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        long now = System.currentTimeMillis();
        if (step < 0 || finished || now - lastSample < SAMPLE_MS || data == null) return;
        lastSample = now;
        lastFrame = data;
        byte[] luma = FrameAnalysis.sampleLuma(data, previewW, previewH, Math.max(1, previewW / 64));
        brightSum += FrameAnalysis.meanLuma(luma);
        samples++;
        if (prevLuma != null) peakMotion = Math.max(peakMotion, FrameAnalysis.motion(prevLuma, luma));
        peakSkin = Math.max(peakSkin, FrameAnalysis.skinRatioNv21(data, previewW, previewH, 0.6));
        prevLuma = luma;
    }

    private void endStep() {
        if (finished) return;
        double bright = samples > 0 ? brightSum / samples : 0;
        if (criteria.stepPassed(bright, peakMotion, peakSkin)) passed++;
        livenessSum += Math.min(1, peakMotion / criteria.minMotion);
        presenceSum += Math.min(1, peakSkin / criteria.minSkin);
        String file = saveSnapshot(step + 1);
        if (file != null) evidence.add(file);
        nextStep();
    }

    private String saveSnapshot(int n) {
        byte[] frame = lastFrame;
        if (frame == null) return null;
        try {
            YuvImage yuv = new YuvImage(frame, ImageFormat.NV21, previewW, previewH, null);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, previewW, previewH), 85, raw);
            Bitmap bmp = BitmapFactory.decodeByteArray(raw.toByteArray(), 0, raw.size());
            Matrix m = new Matrix();
            m.postRotate(cameraOrientation); // upright, as the patient sees themselves unmirrored
            Bitmap upright = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            File out = new File(Store.evidenceDir(this, key), String.format(Locale.ROOT, "step%d_%d.jpg", n, System.currentTimeMillis()));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                upright.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Snapshot failed", e);
            return null;
        }
    }

    private void complete() {
        finished = true;
        releaseCamera();
        AppData data = Store.get(this);
        DoseRecord rec = ScheduleEngine.record(data, key, DoseStatus.TAKEN, LocalDateTime.now());
        if (rec != null) {
            rec.verification = criteria.verdict(passed, STEPS.length, true);
            rec.livenessScore = livenessSum / STEPS.length;
            rec.presenceScore = presenceSum / STEPS.length;
            rec.evidence = new ArrayList<>(evidence);
            rec.note = passed + "/" + STEPS.length + " observation steps confirmed";
        }
        Store.save(this);
        AlarmScheduler.onRecorded(this, key);
        boolean ok = rec != null && rec.verification == com.chemrob.medadherence.core.Verification.AUTO_VERIFIED;
        counter.setText(passed + "/" + STEPS.length + " steps confirmed");
        instruction.setText(ok ? "Dose verified. Well done!" : "Dose recorded. Your pharmacist will review the photos.");
        status.setText("");
        handler.postDelayed(this::finish, 2500);
    }

    /** Cancelled: the dose is still owed, so it rings again after the snooze interval. */
    private void cancel() {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
        AppData data = Store.get(this);
        com.chemrob.medadherence.core.ScheduledDose dose = ScheduleEngine.find(data, key);
        if (dose != null && ScheduleEngine.isDueNow(data, dose, LocalDateTime.now()))
            AlarmReceiver.record(this, key, DoseStatus.SNOOZED);
        finish();
    }

    @Override
    public void onBackPressed() { cancel(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
        super.onDestroy();
    }
}
