package com.chemrob.medadherence.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class IntakeRulesTest {
    // A frontal face; x,y for leftEye, rightEye, noseBase, mouthLeft, mouthRight, mouthBottom, leftCheek, rightCheek.
    static final double[] FACE_A = {40, 40, 60, 40, 50, 55, 42, 68, 58, 68, 50, 74, 35, 55, 65, 55};

    static double[] scaled(double[] xy, double k, double dx) {
        double[] out = xy.clone();
        for (int i = 0; i < out.length; i++) out[i] = out[i] * k + (i % 2 == 0 ? dx : 0);
        return out;
    }

    static FrameObs face() {
        FrameObs f = new FrameObs();
        f.faces = 1; f.yaw = 3; f.pitch = 0; f.eyesOpen = 0.95; f.mouthOpen = 0.05; f.brightness = 0.5;
        f.signature = FaceSignature.compute(FACE_A);
        return f;
    }

    @Test public void signatureIgnoresScaleAndPositionButNotShape() {
        double[] a = FaceSignature.compute(FACE_A);
        double[] sameFarther = FaceSignature.compute(scaled(FACE_A, 0.6, 30));
        assertEquals(0, FaceSignature.distance(a, sameFarther), 1e-9);
        assertTrue(FaceSignature.matches(a, sameFarther));

        double[] other = FACE_A.clone();
        other[5] = 62; other[7] = 80; other[9] = 80; other[11] = 90; // longer nose-to-mouth, wider face shape
        assertFalse(FaceSignature.matches(a, FaceSignature.compute(other)));

        assertNull(FaceSignature.compute(new double[3]));
        double[] nan = FACE_A.clone(); nan[4] = Double.NaN;
        assertNull(FaceSignature.compute(nan));
        assertEquals(Double.POSITIVE_INFINITY, FaceSignature.distance(a, null), 0);
        assertArrayEquals(a, FaceSignature.average(Arrays.asList(a, sameFarther, null)), 1e-9);
    }

    @Test public void stepRules() {
        FrameObs lookingAway = face(); lookingAway.yaw = 40;
        assertFalse(IntakeRules.evaluate(IntakeRules.Step.FACE, Arrays.asList(lookingAway)).passed);
        assertTrue(IntakeRules.evaluate(IntakeRules.Step.FACE, Arrays.asList(lookingAway, face())).passed);

        FrameObs dark = face(); dark.brightness = 0.02;
        IntakeRules.StepResult r = IntakeRules.evaluate(IntakeRules.Step.FACE, Arrays.asList(dark));
        assertFalse(r.passed);
        assertTrue(r.missing.contains("more light"));

        FrameObs showing = face(); showing.handVisible = true;
        assertFalse(IntakeRules.evaluate(IntakeRules.Step.SHOW, Arrays.asList(face())).passed);
        assertTrue(IntakeRules.evaluate(IntakeRules.Step.SHOW, Arrays.asList(showing)).passed);

        FrameObs handAt = face(); handAt.handToMouth = 0.4;
        FrameObs mouthOpen = face(); mouthOpen.mouthOpen = 0.3;
        r = IntakeRules.evaluate(IntakeRules.Step.MOUTH, Arrays.asList(handAt));
        assertFalse(r.passed);
        assertEquals(Arrays.asList("mouth open"), r.missing);
        assertTrue(IntakeRules.evaluate(IntakeRules.Step.MOUTH, Arrays.asList(handAt, mouthOpen)).passed);

        FrameObs tilted = face(); tilted.pitch = 15; tilted.handToMouth = 0.5;
        assertTrue(IntakeRules.evaluate(IntakeRules.Step.DRINK, Arrays.asList(tilted)).passed);
        assertFalse(IntakeRules.evaluate(IntakeRules.Step.DRINK, Arrays.asList(handAt)).passed);

        FrameObs wide = face(); wide.mouthOpen = 0.45;
        assertFalse(IntakeRules.evaluate(IntakeRules.Step.EMPTY, Arrays.asList(wide, wide)).passed);
        assertTrue(IntakeRules.evaluate(IntakeRules.Step.EMPTY, Arrays.asList(wide, wide, wide)).passed);
    }

    @Test public void blinkAndVerdict() {
        FrameObs open = face(), closed = face();
        closed.eyesOpen = 0.1;
        assertFalse(IntakeRules.blinked(Arrays.asList(open, open)));
        assertFalse(IntakeRules.blinked(Arrays.asList(closed, open)));
        assertTrue(IntakeRules.blinked(Arrays.asList(open, closed, open)));

        List<IntakeRules.StepResult> allPass = new ArrayList<>();
        FrameObs showing = face(); showing.handVisible = true;
        FrameObs mouth = face(); mouth.handToMouth = 0.4; mouth.mouthOpen = 0.3;
        FrameObs drink = face(); drink.handToMouth = 0.4; drink.pitch = 12;
        FrameObs wide = face(); wide.mouthOpen = 0.5;
        FrameObs left = face(); left.yaw = -12;
        FrameObs right = face(); right.yaw = 12;
        assertFalse(IntakeRules.evaluate(IntakeRules.Step.FACE, Arrays.asList(face())).passed); // no head turn
        allPass.add(IntakeRules.evaluate(IntakeRules.Step.FACE, Arrays.asList(face(), left, right)));
        allPass.add(IntakeRules.evaluate(IntakeRules.Step.SHOW, Arrays.asList(showing)));
        allPass.add(IntakeRules.evaluate(IntakeRules.Step.MOUTH, Arrays.asList(mouth)));
        allPass.add(IntakeRules.evaluate(IntakeRules.Step.DRINK, Arrays.asList(drink)));
        allPass.add(IntakeRules.evaluate(IntakeRules.Step.EMPTY, Arrays.asList(wide, wide, wide)));
        List<FrameObs> session = Arrays.asList(open, closed, open, showing, mouth, drink, wide);

        double[] enrolled = FaceSignature.compute(FACE_A);
        IntakeRules.Verdict v = IntakeRules.verdict(allPass, session, enrolled);
        assertEquals(Verification.AUTO_VERIFIED, v.verification);
        assertEquals(1.0, v.faceMatch, 1e-9);
        assertTrue(v.summary.contains("5/5 steps"));

        // A still photo never blinks.
        assertEquals(Verification.NEEDS_REVIEW, IntakeRules.verdict(allPass, Arrays.asList(open, open), enrolled).verification);

        // Someone else's face.
        double[] other = FACE_A.clone(); other[5] = 62; other[7] = 80; other[9] = 80; other[11] = 90;
        IntakeRules.Verdict wrong = IntakeRules.verdict(allPass, session, FaceSignature.compute(other));
        assertEquals(Verification.NEEDS_REVIEW, wrong.verification);
        assertEquals(0.0, wrong.faceMatch, 1e-9);

        // No enrolled face: can still auto-verify on the other checks.
        assertEquals(Verification.AUTO_VERIFIED, IntakeRules.verdict(allPass, session, null).verification);
    }

    @Test public void profileFaceRoundTrip() throws Exception {
        AppData d = new AppData();
        d.profile.name = "Asha";
        d.profile.facePhoto = "/x/face.jpg";
        d.profile.faceSignature = FaceSignature.compute(FACE_A);
        AppData back = JsonCodec.fromJson(JsonCodec.toJson(d));
        assertTrue(back.profile.hasFace());
        assertArrayEquals(d.profile.faceSignature, back.profile.faceSignature, 1e-9);
        assertEquals("/x/face.jpg", back.profile.facePhoto);
        assertFalse(JsonCodec.fromJson(JsonCodec.toJson(new AppData())).profile.hasFace());
    }
}
