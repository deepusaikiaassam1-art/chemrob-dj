package com.chemrob.medadherence.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class FaceMatchTest {
    @Test public void alignmentMatchesReference() {
        // Reference values from skimage SimilarityTransform (the ArcFace/SFace alignment).
        double[] src = {210, 240, 300, 228, 258, 290, 222, 338, 296, 330};
        double[] t = FaceMatch.alignTransform(src);
        double[] want = {0.397677, -0.026766, -38.622629, 0.026766, 0.397677, -48.40073};
        assertArrayEquals(want, t, 1e-4);

        // Template maps onto itself.
        assertArrayEquals(new double[]{1, 0, 0, 0, 1, 0}, FaceMatch.alignTransform(FaceMatch.TEMPLATE), 1e-9);
        assertNull(FaceMatch.alignTransform(new double[10]));
        assertNull(FaceMatch.alignTransform(new double[4]));
    }

    @Test public void templateOrderUsesImagePosition() {
        double[] l = {10, 20}, r = {50, 21}, n = {30, 35}, ml = {15, 50}, mr = {45, 50};
        double[] a = FaceMatch.templateOrder(r, l, n, mr, ml);
        assertArrayEquals(new double[]{10, 20, 50, 21, 30, 35, 15, 50, 45, 50}, a, 0);
        assertNull(FaceMatch.templateOrder(l, r, null, ml, mr));
    }

    @Test public void similarity() {
        float[] a = {1, 0, 0}, b = {2, 0, 0}, c = {0, 1, 0}, d = {1, 1, 0};
        assertEquals(1, FaceMatch.cosine(a, b), 1e-9);
        assertEquals(0, FaceMatch.cosine(a, c), 1e-9);
        assertTrue(Double.isNaN(FaceMatch.cosine(a, new float[2])));
        assertEquals(1, FaceMatch.normalize(b)[0], 1e-6);
        assertEquals(Math.sqrt(0.5), FaceMatch.best(Arrays.asList(c, a), d), 1e-6);
        assertTrue(Double.isNaN(FaceMatch.best(new ArrayList<>(), d)));
        assertTrue(FaceMatch.isMatch(0.5));
        assertFalse(FaceMatch.isMatch(0.3));
        assertFalse(FaceMatch.isMatch(Double.NaN));
        // Left (a) and right (c) views differ, but each matches the straight view (d).
        assertEquals(Math.sqrt(0.5), FaceMatch.consistency(Arrays.asList(a, d, c)), 1e-6);
        // A view that matches nothing (a second person) pulls it to 0.
        assertEquals(0, FaceMatch.consistency(Arrays.asList(a, b, c)), 1e-9);
        assertTrue(Double.isNaN(FaceMatch.consistency(Arrays.asList(a))));
    }

    static FrameObs sim(double s) {
        FrameObs f = IntakeRulesTest.face();
        f.faceSim = s;
        return f;
    }

    @Test public void recognitionDecidesVerdict() {
        assertTrue(Double.isNaN(IntakeRules.recognitionRate(Arrays.asList(sim(0.8), sim(0.9)))));
        assertEquals(0.75, IntakeRules.recognitionRate(Arrays.asList(sim(0.8), sim(0.9), sim(0.1), sim(0.6), IntakeRulesTest.face())), 1e-9);

        List<IntakeRules.StepResult> steps = new ArrayList<>();
        steps.add(new IntakeRules.StepResult(IntakeRules.Step.FACE, true));
        FrameObs closed = sim(0.8); closed.eyesOpen = 0.1;
        List<FrameObs> patient = Arrays.asList(sim(0.8), closed, sim(0.7), sim(0.9));
        IntakeRules.Verdict v = IntakeRules.verdict(steps, patient, null, true);
        assertEquals(Verification.AUTO_VERIFIED, v.verification);
        assertTrue(v.recognition);
        assertTrue(v.summary, v.summary.contains("recognised as the patient in 100%"));

        FrameObs closedOther = sim(0.1); closedOther.eyesOpen = 0.1;
        List<FrameObs> someoneElse = Arrays.asList(sim(0.1), closedOther, sim(0.2), sim(0.15));
        assertEquals(Verification.NEEDS_REVIEW, IntakeRules.verdict(steps, someoneElse, null, true).verification);

        // Recognition enrolled but no checks ran: never auto-verified.
        FrameObs c = IntakeRulesTest.face(); c.eyesOpen = 0.1;
        List<FrameObs> none = Arrays.asList(IntakeRulesTest.face(), c, IntakeRulesTest.face());
        assertEquals(Verification.NEEDS_REVIEW, IntakeRules.verdict(steps, none, null, true).verification);
    }

    @Test public void embeddingsRoundTrip() throws Exception {
        AppData d = new AppData();
        d.profile.name = "Asha";
        d.profile.faceEmbeddings.add(new float[]{0.1f, -0.2f, 0.3f});
        d.profile.faceEmbeddings.add(new float[]{0.4f, 0.5f, -0.6f});
        AppData back = JsonCodec.fromJson(JsonCodec.toJson(d));
        assertTrue(back.profile.hasFaceRecognition());
        assertEquals(2, back.profile.faceEmbeddings.size());
        assertArrayEquals(new float[]{0.4f, 0.5f, -0.6f}, back.profile.faceEmbeddings.get(1), 1e-5f);
        assertFalse(new AppData().profile.hasFaceRecognition());
    }
}
