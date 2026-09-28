package com.chemrob.medadherence.ui;

import android.graphics.PointF;
import android.graphics.Rect;

import com.chemrob.medadherence.core.FaceSignature;
import com.chemrob.medadherence.core.FrameAnalysis;
import com.chemrob.medadherence.core.FrameObs;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.List;

/**
 * On-device AI (Google ML Kit, bundled models, works offline): face detection with landmarks,
 * contours and eye-open classification, plus body-pose detection for the hands. Turns one camera
 * frame into a {@link FrameObs} for the intake rules. Nothing leaves the phone.
 */
public final class Vision {
    public interface Callback { void onResult(FrameObs obs, Face largestFace); }

    private final FaceDetector faces;
    private final PoseDetector pose;
    private boolean busy;

    public Vision(boolean withHands) {
        faces = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.2f)
                .build());
        pose = withHands ? PoseDetection.getClient(new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()) : null;
    }

    /** True while a frame is being analysed; skip frames meanwhile. */
    public boolean busy() { return busy; }

    /**
     * @param rotation degrees to turn the frame upright (camera orientation for a portrait app).
     * The callback runs on the main thread; on failure it gets an observation without face data.
     */
    public void analyze(byte[] nv21, int w, int h, int rotation, long timeMs, Callback cb) {
        busy = true;
        FrameObs obs = new FrameObs();
        obs.timeMs = timeMs;
        obs.brightness = FrameAnalysis.meanLuma(FrameAnalysis.sampleLuma(nv21, w, h, Math.max(1, w / 64)));
        InputImage img = InputImage.fromByteArray(nv21, w, h, rotation, InputImage.IMAGE_FORMAT_NV21);
        faces.process(img)
                .addOnSuccessListener(list -> {
                    Face f = fillFace(obs, list);
                    if (pose == null || f == null) { busy = false; cb.onResult(obs, f); return; }
                    pose.process(img)
                            .addOnSuccessListener(p -> { fillHands(obs, f, p); busy = false; cb.onResult(obs, f); })
                            .addOnFailureListener(e -> { busy = false; cb.onResult(obs, f); });
                })
                .addOnFailureListener(e -> { busy = false; cb.onResult(obs, null); });
    }

    private static Face fillFace(FrameObs obs, List<Face> list) {
        obs.faces = list.size();
        Face best = null;
        for (Face f : list)
            if (best == null || area(f.getBoundingBox()) > area(best.getBoundingBox())) best = f;
        if (best == null) return null;
        obs.yaw = best.getHeadEulerAngleY();
        obs.pitch = best.getHeadEulerAngleX();
        Float l = best.getLeftEyeOpenProbability(), r = best.getRightEyeOpenProbability();
        if (l != null && r != null) obs.eyesOpen = Math.min(l, r);

        PointF ml = pos(best, FaceLandmark.MOUTH_LEFT), mr = pos(best, FaceLandmark.MOUTH_RIGHT);
        FaceContour upper = best.getContour(FaceContour.UPPER_LIP_BOTTOM), lower = best.getContour(FaceContour.LOWER_LIP_TOP);
        if (ml != null && mr != null && upper != null && lower != null
                && !upper.getPoints().isEmpty() && !lower.getPoints().isEmpty()) {
            PointF u = upper.getPoints().get(upper.getPoints().size() / 2);
            PointF d = lower.getPoints().get(lower.getPoints().size() / 2);
            double width = Math.hypot(mr.x - ml.x, mr.y - ml.y);
            if (width > 1) obs.mouthOpen = Math.max(0, Math.hypot(d.x - u.x, d.y - u.y)) / width;
        }

        int[] ids = {FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT,
                FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_BOTTOM, FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK};
        double[] xy = new double[ids.length * 2];
        for (int i = 0; i < ids.length; i++) {
            PointF p = pos(best, ids[i]);
            xy[i * 2] = p == null ? Double.NaN : p.x;
            xy[i * 2 + 1] = p == null ? Double.NaN : p.y;
        }
        obs.signature = FaceSignature.compute(xy);
        return best;
    }

    private static void fillHands(FrameObs obs, Face face, Pose p) {
        PointF ml = pos(face, FaceLandmark.MOUTH_LEFT), mr = pos(face, FaceLandmark.MOUTH_RIGHT);
        Rect box = face.getBoundingBox();
        double fw = Math.max(1, box.width());
        float mx = ml != null && mr != null ? (ml.x + mr.x) / 2 : box.centerX();
        float my = ml != null && mr != null ? (ml.y + mr.y) / 2 : box.bottom - box.height() / 4f;
        int[] hands = {PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_INDEX, PoseLandmark.RIGHT_INDEX,
                PoseLandmark.LEFT_THUMB, PoseLandmark.RIGHT_THUMB, PoseLandmark.LEFT_PINKY, PoseLandmark.RIGHT_PINKY};
        double best = Double.NaN;
        for (int id : hands) {
            PoseLandmark lm = p.getPoseLandmark(id);
            if (lm == null || lm.getInFrameLikelihood() < 0.5f) continue;
            obs.handVisible = true;
            double d = Math.hypot(lm.getPosition().x - mx, lm.getPosition().y - my) / fw;
            if (Double.isNaN(best) || d < best) best = d;
        }
        obs.handToMouth = best;
    }

    private static PointF pos(Face f, int landmark) {
        FaceLandmark lm = f.getLandmark(landmark);
        return lm == null ? null : lm.getPosition();
    }

    private static int area(Rect r) { return r.width() * r.height(); }

    public void close() {
        try { faces.close(); } catch (Exception ignored) { }
        try { if (pose != null) pose.close(); } catch (Exception ignored) { }
    }
}
