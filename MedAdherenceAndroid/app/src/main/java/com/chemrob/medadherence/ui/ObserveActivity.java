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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chemrob.medadherence.R;
import com.chemrob.medadherence.Store;
import com.chemrob.medadherence.alarm.AlarmReceiver;
import com.chemrob.medadherence.alarm.AlarmScheduler;
import com.chemrob.medadherence.alarm.Notifications;
import com.chemrob.medadherence.core.AppData;
import com.chemrob.medadherence.core.DoseKey;
import com.chemrob.medadherence.core.DoseRecord;
import com.chemrob.medadherence.core.DoseStatus;
import com.chemrob.medadherence.core.FaceMatch;
import com.chemrob.medadherence.core.FaceSignature;
import com.chemrob.medadherence.core.FrameObs;
import com.chemrob.medadherence.core.I18n;
import com.chemrob.medadherence.core.IntakeRules;
import com.chemrob.medadherence.core.Medication;
import com.chemrob.medadherence.core.ScheduleEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * Observed-dose mode: the front camera watches the patient take the medicine while on-device AI
 * (ML Kit face + pose detection, see {@link Vision}) checks each guided step: a live, matching face,
 * the medicine shown, hand to an open mouth, drinking with the head tilted back and an empty open
 * mouth. A photo is kept for every step. All checks pass = auto-verified; otherwise the pharmacist
 * reviews the photos next to the enrolled face.
 */
@SuppressWarnings("deprecation")
public class ObserveActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "MedAdherence";
    private static final IntakeRules.Step[] STEPS = IntakeRules.Step.values();
    /** Each step runs at least MIN and at most MAX; it ends early once the AI confirms it. */
    private static final long STEP_MIN_MS = 3500, STEP_MAX_MS = 12000;
    private static final int REQ_CAMERA = 7;

    private Vision vision;
    private double[] enrolledFace;
    private List<float[]> enrolledViews;   // face fingerprints from the profile scan (may be empty)
    private FaceRecognizer recognizer;     // null: no model, or patient not enrolled with it
    private long lastRecognitionAt;
    private double lastSim = Double.NaN;
    private final List<FrameObs> stepFrames = new ArrayList<>(), allFrames = new ArrayList<>();
    private final List<IntakeRules.StepResult> results = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String key;
    private Medication med;
    private Camera camera;
    private int cameraOrientation;
    private int previewW, previewH;
    private SurfaceHolder holder;
    private TextView instruction, status, counter, secondsLeft, youChip;
    private View stepBar;
    private LinearLayout checklist;
    private static final String[] STEP_NAMES = {"Look and turn", "Show the medicine", "Medicine to mouth", "Drink water", "Open mouth wide"};
    private static final int DARK = Color.parseColor("#111320");

    // step state
    private int step = -1;
    private long stepStart;
    private byte[] lastFrame;
    private boolean spokeHint;
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
        enrolledFace = Store.get(this).profile.faceSignature;
        enrolledViews = Store.get(this).profile.faceEmbeddings;
        if (Store.get(this).profile.hasFaceRecognition()) recognizer = FaceRecognizer.get(this);
        vision = new Vision(true);

        Ui.fonts(this);
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(DARK);

        // Camera with a face guide, and the medicine's name above it.
        FrameLayout cam = new FrameLayout(this);
        SurfaceView surface = new SurfaceView(this);
        cam.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        View oval = new View(this);
        GradientDrawable og = new GradientDrawable();
        og.setShape(GradientDrawable.OVAL);
        og.setStroke(Ui.dp(this, 4), Color.argb(140, 255, 255, 255), Ui.dp(this, 8), Ui.dp(this, 6));
        oval.setBackground(og);
        cam.addView(oval, new FrameLayout.LayoutParams(Ui.dp(this, 230), Ui.dp(this, 280), Gravity.CENTER));
        youChip = new TextView(this);
        youChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        youChip.setTypeface(Ui.medium(), Typeface.BOLD);
        youChip.setTextColor(DARK);
        youChip.setBackground(Ui.rounded(this, Color.parseColor("#4CD08E"), 18));
        youChip.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        youChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams yl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        yl.bottomMargin = Ui.dp(this, 16);
        cam.addView(youChip, yl);

        LinearLayout bar = Ui.hbox(this);
        bar.setBackgroundColor(Color.argb(150, 17, 19, 32));
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 32), Ui.dp(this, 16), Ui.dp(this, 12));
        TextView close = new TextView(this);
        close.setBackground(Ui.rounded(this, Color.argb(36, 255, 255, 255), 24));
        close.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_close, Color.WHITE), null, null, null);
        close.setPadding(Ui.dp(this, 12), 0, 0, 0);
        close.setContentDescription(t("Cancel"));
        close.setOnClickListener(v -> cancel());
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        xl.rightMargin = Ui.dp(this, 12);
        bar.addView(close, xl);
        LinearLayout names = Ui.vbox(this);
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mn = Ui.text(names, "", 18, Color.WHITE, true);
        mn.setText((med.name + " " + med.dose).trim());
        ((LinearLayout.LayoutParams) mn.getLayoutParams()).topMargin = 0;
        TextView note = Ui.text(names, "Camera dose · nothing is uploaded", 14, Color.parseColor("#A3A7BD"), false);
        ((LinearLayout.LayoutParams) note.getLayoutParams()).topMargin = 0;
        cam.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(cam, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // The sheet: which step, what to do, and the five steps ticked off one by one.
        LinearLayout sheet = Ui.vbox(this);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Color.WHITE);
        float r = Ui.dp(this, 30);
        sg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sg);
        sheet.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 24));
        LinearLayout head = Ui.hbox(this);
        sheet.addView(head);
        counter = Ui.text(head, "", 15, Ui.PRIMARY_LIGHT, true);
        counter.setLetterSpacing(0.06f);
        ((LinearLayout.LayoutParams) counter.getLayoutParams()).topMargin = 0;
        secondsLeft = Ui.text(head, "", 15, Ui.MUTED_LIGHT, false);
        secondsLeft.setGravity(Gravity.END);
        ((LinearLayout.LayoutParams) secondsLeft.getLayoutParams()).topMargin = 0;
        instruction = Ui.text(sheet, "Starting camera...", 26, Ui.INK_LIGHT, true);
        ((LinearLayout.LayoutParams) instruction.getLayoutParams()).topMargin = Ui.dp(this, 10);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.rounded(this, Color.parseColor("#ECEEF8"), 5));
        stepBar = new View(this);
        stepBar.setBackground(Ui.rounded(this, Ui.PRIMARY_LIGHT, 5));
        track.addView(stepBar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10));
        tl.topMargin = Ui.dp(this, 14);
        sheet.addView(track, tl);
        checklist = Ui.vbox(this);
        sheet.addView(checklist, Ui.matchWrap(this, 10));
        status = Ui.text(sheet, "", 14, Ui.MUTED_LIGHT, false);
        Ui.text(sheet, "Blink once at any point. If a step can't be checked, the pharmacist looks at the photos.", 14, Ui.MUTED_LIGHT, false);
        root.addView(sheet, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        drawChecklist();

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
            instruction.setText(t("Camera permission is needed to observe this dose."));
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
            instruction.setText(t("The camera could not be opened."));
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
        counter.setText(tf("Step %d of %d", step + 1, STEPS.length).toUpperCase(I18n.locale()));
        instruction.setText(t(STEPS[step].instruction));
        drawChecklist();
        if (step == 0) {
            Voice.say(this, "Let's take your %s together. Follow my instructions.", med.name);
            Voice.then(this, STEPS[0].instruction);
        } else Voice.then(this, STEPS[step].instruction);
        spokeHint = false;
        stepStart = System.currentTimeMillis();
        stepFrames.clear();
        handler.post(this::tick);
    }

    /** Live feedback, and ends the step as soon as the AI has seen what it needs. */
    private void tick() {
        if (finished || step < 0 || step >= STEPS.length) return;
        long elapsed = System.currentTimeMillis() - stepStart;
        IntakeRules.StepResult r = IntakeRules.evaluate(STEPS[step], stepFrames);
        FrameObs last = stepFrames.isEmpty() ? null : stepFrames.get(stepFrames.size() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append(last != null && last.oneFace() ? t("[OK] face") : t("[ .. ] face"));
        if (recognizer != null) {
            if (!Double.isNaN(lastSim))
                sb.append(FaceMatch.isMatch(lastSim)
                        ? "   " + tf("[OK] it's you (%.0f%%)", lastSim * 100)
                        : "   " + t("[ !! ] not recognised"));
        } else if (enrolledFace != null && last != null && last.signature != null)
            sb.append("   ").append(FaceSignature.matches(enrolledFace, last.signature) ? t("[OK] it's you") : t("[ .. ] face match"));
        if (r.passed) sb.append('\n').append(t("[OK] step confirmed"));
        else if (elapsed > 2000 && !r.missing.isEmpty()) {
            List<String> need = new ArrayList<>();
            for (String m : r.missing) need.add(t(m));
            sb.append('\n').append(tf("Need: %s", String.join(", ", need)));
        }
        if (!r.passed && !spokeHint && elapsed > 6000 && !r.missing.isEmpty()) {
            spokeHint = true;
            Voice.say(this, "I still need to see: %s.", Voice.list(r.missing));
        }
        status.setText(sb.toString());
        long left = Math.max(0, (STEP_MAX_MS - elapsed + 999) / 1000);
        secondsLeft.setText(r.passed ? t("Confirmed") : left == 1 ? t("1 second left") : tf("%d seconds left", left));
        View track = (View) stepBar.getParent();
        double done = (step + Math.min(1.0, (double) elapsed / STEP_MAX_MS)) / STEPS.length;
        stepBar.getLayoutParams().width = (int) (track.getWidth() * done);
        stepBar.requestLayout();
        boolean you = recognizer != null && !Double.isNaN(lastSim) && FaceMatch.isMatch(lastSim);
        youChip.setVisibility(you ? View.VISIBLE : View.GONE);
        if (you) youChip.setText(tf("This is %s", Store.get(this).profile.firstName()));

        if ((r.passed && elapsed >= STEP_MIN_MS) || elapsed >= STEP_MAX_MS) { endStep(r); return; }
        handler.postDelayed(this::tick, 250);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (step < 0 || finished || data == null || vision.busy()) return;
        lastFrame = data;
        final int s = step;
        vision.analyze(data, previewW, previewH, cameraOrientation, System.currentTimeMillis(), (obs, face) -> {
            if (finished || s != step) return;
            stepFrames.add(obs);
            allFrames.add(obs);
            recognise(data, obs, face);
        });
    }

    /** Every ~0.7 s, checks the face in view against the patient's enrolled face fingerprints. */
    private void recognise(byte[] frame, FrameObs obs, com.google.mlkit.vision.face.Face face) {
        if (recognizer == null || face == null || !obs.oneFace() || recognizer.busy()) return;
        // Only clear views count: not while a hand or glass covers the face or the head is tilted back.
        if (Math.abs(obs.yaw) > 25 || Math.abs(obs.pitch) > 20 || obs.mouthOpen > 0.25
                || (!Double.isNaN(obs.handToMouth) && obs.handToMouth < 0.8)) return;
        if (System.currentTimeMillis() - lastRecognitionAt < 700) return;
        double[] pts = Vision.alignPoints(face);
        if (pts == null) return;
        lastRecognitionAt = System.currentTimeMillis();
        recognizer.embedAsync(frame, previewW, previewH, cameraOrientation, pts, e -> {
            if (e == null || finished) return;
            obs.faceSim = FaceMatch.best(enrolledViews, e); // obs is already in allFrames
            lastSim = obs.faceSim;
        });
    }

    private void endStep(IntakeRules.StepResult r) {
        if (finished) return;
        if (r.passed) Voice.say(this, "Good.");
        results.add(r);
        passed.add(r.passed);
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
        IntakeRules.Verdict v = IntakeRules.verdict(results, allFrames, enrolledFace, recognizer != null);
        AppData data = Store.get(this);
        DoseRecord rec = ScheduleEngine.record(data, key, DoseStatus.TAKEN, LocalDateTime.now());
        if (rec != null) {
            rec.verification = v.verification;
            rec.livenessScore = v.live ? 1 : 0;
            rec.presenceScore = Double.isNaN(v.faceMatch) ? 0 : v.faceMatch;
            rec.evidence = new ArrayList<>(evidence);
            rec.note = v.summary;
        }
        Store.save(this);
        AlarmScheduler.onRecorded(this, key);
        counter.setText(tf("%d/%d steps confirmed", v.stepsPassed, v.stepsTotal).toUpperCase(I18n.locale()));
        secondsLeft.setText("");
        youChip.setVisibility(View.GONE);
        stepBar.getLayoutParams().width = ((View) stepBar.getParent()).getWidth();
        stepBar.requestLayout();
        drawChecklist();
        String done = v.verification == com.chemrob.medadherence.core.Verification.AUTO_VERIFIED
                ? "Dose verified. Well done!" : "Dose recorded. Your pharmacist will review the photos.";
        instruction.setText(t(done));
        status.setText(v.summary);
        Voice.then(this, done);
        handler.postDelayed(this::finish, 3000);
    }

    /** Cancelled: the dose is still owed, so it rings again after the snooze interval. */
    private void cancel() {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
        Voice.say(this, "Cancelled. I will remind you again soon.");
        AppData data = Store.get(this);
        com.chemrob.medadherence.core.ScheduledDose dose = ScheduleEngine.find(data, key);
        if (dose != null && ScheduleEngine.isDueNow(data, dose, LocalDateTime.now()))
            AlarmReceiver.record(this, key, DoseStatus.SNOOZED);
        finish();
    }

    private final List<Boolean> passed = new ArrayList<>();

    /** The five steps: ticked (green) when confirmed, the current one filled in, the rest waiting. */
    private void drawChecklist() {
        if (checklist == null) return;
        checklist.removeAllViews();
        for (int i = 0; i < STEP_NAMES.length; i++) {
            boolean done = i < step || finished, current = i == step && !finished;
            boolean ok = i < passed.size() && passed.get(i);
            LinearLayout row = Ui.hbox(this);
            row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
            FrameLayout dot = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            int ink;
            if (done) { g.setColor(ok ? Ui.GOOD_LIGHT : Color.parseColor("#C77C00")); ink = Color.WHITE; }
            else if (current) { g.setColor(Ui.PRIMARY_LIGHT); ink = Color.WHITE; }
            else { g.setColor(Color.WHITE); g.setStroke(Ui.dp(this, 2), Color.parseColor("#DCDEEA")); ink = Ui.MUTED_LIGHT; }
            dot.setBackground(g);
            if (done && ok) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(tinted(R.drawable.ic_check, ink));
                dot.addView(iv, new FrameLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18), Gravity.CENTER));
            } else {
                TextView n = new TextView(this);
                n.setText(done ? "!" : String.valueOf(i + 1));
                n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                n.setTypeface(Ui.medium(), Typeface.BOLD);
                n.setTextColor(ink);
                n.setGravity(Gravity.CENTER);
                dot.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30));
            dl.rightMargin = Ui.dp(this, 12);
            row.addView(dot, dl);
            TextView label = Ui.text(row, STEP_NAMES[i], 17, done ? (ok ? Ui.GOOD_LIGHT : Ui.MUTED_LIGHT) : current ? Ui.INK_LIGHT : Ui.MUTED_LIGHT, current);
            ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = 0;
            checklist.addView(row);
        }
    }

    private android.graphics.drawable.Drawable tinted(int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        d.setBounds(0, 0, Ui.dp(this, 24), Ui.dp(this, 24));
        return d;
    }

    @Override
    public void onBackPressed() { cancel(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
        if (vision != null) vision.close();
        super.onDestroy();
    }
}
