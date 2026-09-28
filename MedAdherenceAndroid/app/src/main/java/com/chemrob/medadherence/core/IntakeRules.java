package com.chemrob.medadherence.core;

import java.util.ArrayList;
import java.util.List;

/**
 * AI checks for a camera-observed dose. Each guided step has a rule evaluated over the frames the
 * on-device AI analysed during that step. The whole session also needs a blink (a live person, not a
 * photo) and, when the patient enrolled a face, a face that matches it.
 *
 * What this can and cannot prove: it confirms a live, matching face that brought a hand to an open
 * mouth, drank with the head tilted back and then showed an open mouth. It cannot see a tablet being
 * swallowed; doses that fail any check are left for the pharmacist to review with the photos.
 */
public final class IntakeRules {
    private IntakeRules() {}

    public enum Step {
        FACE("Look at the camera, then slowly turn your head a little to each side"),
        SHOW("Hold the medicine up next to your face"),
        MOUTH("Put the medicine in your mouth"),
        DRINK("Drink water and swallow"),
        EMPTY("Open your mouth wide to show it is empty");

        public final String instruction;
        Step(String instruction) { this.instruction = instruction; }
    }

    // Thresholds (face-width units, degrees, probabilities).
    public static final double HAND_AT_MOUTH = 0.75;
    public static final double MOUTH_OPEN = 0.22;
    public static final double MOUTH_WIDE = 0.35;
    public static final double DRINK_PITCH = 8;      // head tilted back by at least this many degrees
    public static final double MIN_BRIGHTNESS = 0.12;
    public static final int MIN_WIDE_FRAMES = 3;     // mouth held open over several frames
    public static final double HEAD_TURN = 20;       // left-right head turn range (live person, not a photo)

    public static final class StepResult {
        public final Step step;
        public final boolean passed;
        public final List<String> missing = new ArrayList<>(); // what the patient still needs to do

        StepResult(Step step, boolean passed) { this.step = step; this.passed = passed; }
    }

    public static StepResult evaluate(Step step, List<FrameObs> frames) {
        boolean face = false, lit = false, hand = false, handAtMouth = false, mouthOpen = false, tilt = false;
        int wide = 0;
        for (FrameObs f : frames) {
            if (!Double.isNaN(f.brightness) && f.brightness >= MIN_BRIGHTNESS) lit = true;
            if (f.oneFace()) face = true;
            if (f.handVisible) hand = true;
            boolean atMouth = !Double.isNaN(f.handToMouth) && f.handToMouth <= HAND_AT_MOUTH;
            if (atMouth) handAtMouth = true;
            if (!Double.isNaN(f.mouthOpen) && f.mouthOpen >= MOUTH_OPEN) mouthOpen = true;
            if (f.oneFace() && !Double.isNaN(f.pitch) && f.pitch >= DRINK_PITCH) tilt = true;
            if (f.frontal() && !Double.isNaN(f.mouthOpen) && f.mouthOpen >= MOUTH_WIDE) wide++;
        }
        boolean frontal = false;
        for (FrameObs f : frames) if (f.frontal()) { frontal = true; break; }

        List<String> miss = new ArrayList<>();
        if (!lit) miss.add("more light");
        switch (step) {
            case FACE:
                if (!frontal) miss.add("face looking at the camera");
                else if (yawRange(frames) < HEAD_TURN) miss.add("head turned a little to each side");
                break;
            case SHOW:
                if (!face) miss.add("face in view");
                if (!hand) miss.add("hand with the medicine in view");
                break;
            case MOUTH:
                if (!handAtMouth) miss.add("hand at the mouth");
                if (!mouthOpen) miss.add("mouth open");
                break;
            case DRINK:
                if (!handAtMouth) miss.add("glass at the mouth");
                if (!tilt) miss.add("head tilted back to drink");
                break;
            case EMPTY:
                if (wide < MIN_WIDE_FRAMES) miss.add("mouth held wide open");
                break;
        }
        StepResult r = new StepResult(step, miss.isEmpty());
        r.missing.addAll(miss);
        return r;
    }

    /** Range of left-right head angle over frames with one face (degrees). */
    public static double yawRange(List<FrameObs> frames) {
        double lo = Double.NaN, hi = Double.NaN;
        for (FrameObs f : frames) {
            if (!f.oneFace() || Double.isNaN(f.yaw)) continue;
            lo = Double.isNaN(lo) ? f.yaw : Math.min(lo, f.yaw);
            hi = Double.isNaN(hi) ? f.yaw : Math.max(hi, f.yaw);
        }
        return Double.isNaN(lo) ? 0 : hi - lo;
    }

    /** A blink anywhere in the session: eyes seen open, then closed, then open again. */
    public static boolean blinked(List<FrameObs> frames) {
        int state = 0; // 0 = waiting for open, 1 = open seen, 2 = closed seen
        for (FrameObs f : frames) {
            if (Double.isNaN(f.eyesOpen)) continue;
            if (state == 0 && f.eyesOpen >= 0.7) state = 1;
            else if (state == 1 && f.eyesOpen <= 0.3) state = 2;
            else if (state == 2 && f.eyesOpen >= 0.7) return true;
        }
        return false;
    }

    /** Face-recognition checks needed before a match rate counts. */
    public static final int MIN_FACE_CHECKS = 3;

    /**
     * Share of face-recognition checks that recognised the enrolled patient; NaN when there were
     * fewer than {@link #MIN_FACE_CHECKS} (e.g. no recognition model or no enrolled fingerprints).
     */
    public static double recognitionRate(List<FrameObs> frames) {
        int n = 0, ok = 0;
        for (FrameObs f : frames) {
            if (Double.isNaN(f.faceSim)) continue;
            n++;
            if (FaceMatch.isMatch(f.faceSim)) ok++;
        }
        return n < MIN_FACE_CHECKS ? Double.NaN : (double) ok / n;
    }

    /** Share of frames with one face whose signature matches the enrolled one; NaN when not measurable. */
    public static double faceMatchRate(double[] enrolled, List<FrameObs> frames) {
        if (enrolled == null) return Double.NaN;
        int n = 0, ok = 0;
        for (FrameObs f : frames) {
            if (!f.frontal() || f.signature == null) continue;
            n++;
            if (FaceSignature.matches(enrolled, f.signature)) ok++;
        }
        return n == 0 ? Double.NaN : (double) ok / n;
    }

    /** Overall outcome of a session. */
    public static final class Verdict {
        public int stepsPassed, stepsTotal;
        public boolean live;
        public double faceMatch = Double.NaN; // NaN = no enrolled face / not measurable
        public boolean recognition;           // faceMatch came from the face-recognition model
        public Verification verification;
        public String summary;
    }

    public static Verdict verdict(List<StepResult> steps, List<FrameObs> all, double[] enrolledFace) {
        return verdict(steps, all, enrolledFace, false);
    }

    /**
     * @param recognitionEnrolled the patient enrolled with the face-recognition model; then the
     *                            recognition rate decides the face check (the geometry signature is
     *                            only a fallback for older enrolments)
     */
    public static Verdict verdict(List<StepResult> steps, List<FrameObs> all, double[] enrolledFace, boolean recognitionEnrolled) {
        Verdict v = new Verdict();
        v.stepsTotal = steps.size();
        for (StepResult s : steps) if (s.passed) v.stepsPassed++;
        v.live = blinked(all);
        boolean faceOk;
        if (recognitionEnrolled) {
            v.faceMatch = recognitionRate(all);
            v.recognition = true;
            faceOk = !Double.isNaN(v.faceMatch) && v.faceMatch >= 0.7;
        } else {
            v.faceMatch = faceMatchRate(enrolledFace, all);
            faceOk = Double.isNaN(v.faceMatch) ? enrolledFace == null : v.faceMatch >= 0.6;
        }
        boolean ok = v.stepsTotal > 0 && v.stepsPassed == v.stepsTotal && v.live && faceOk;
        v.verification = ok ? Verification.AUTO_VERIFIED : Verification.NEEDS_REVIEW;
        StringBuilder sb = new StringBuilder();
        sb.append(v.stepsPassed).append('/').append(v.stepsTotal).append(" steps confirmed by AI");
        sb.append(v.live ? ", blink seen" : ", no blink seen");
        if (recognitionEnrolled) {
            if (Double.isNaN(v.faceMatch)) sb.append(", face not recognised (too few clear views)");
            else sb.append(String.format(java.util.Locale.ROOT, ", recognised as the patient in %.0f%% of checks", v.faceMatch * 100));
        } else if (enrolledFace == null) sb.append(", no face enrolled");
        else if (Double.isNaN(v.faceMatch)) sb.append(", face not measurable");
        else sb.append(String.format(java.util.Locale.ROOT, ", face match %.0f%%", v.faceMatch * 100));
        v.summary = sb.toString();
        return v;
    }
}
