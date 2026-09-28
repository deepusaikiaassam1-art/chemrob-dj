package com.chemrob.medadherence.core;

import java.util.List;

/**
 * A simple face-geometry signature: distances between facial landmarks (eyes, nose, mouth corners,
 * cheeks) divided by the distance between the eyes. It stays about the same for one person across
 * sessions and differs between many people, so it can flag "this may be someone else".
 *
 * It is NOT biometric face recognition and can be fooled or can mis-flag, so a mismatch sends the
 * dose to the pharmacist (who sees both faces) rather than rejecting it outright.
 */
public final class FaceSignature {
    private FaceSignature() {}

    /** Landmark order expected by {@link #compute}. */
    public static final String[] LANDMARKS = {"leftEye", "rightEye", "noseBase", "mouthLeft", "mouthRight", "mouthBottom", "leftCheek", "rightCheek"};

    /** Mean absolute difference below this is treated as the same person. */
    public static final double MATCH_THRESHOLD = 0.12;

    /** @param xy x,y pairs in {@link #LANDMARKS} order (16 numbers). Returns null if unusable. */
    public static double[] compute(double[] xy) {
        if (xy == null || xy.length != LANDMARKS.length * 2) return null;
        for (double v : xy) if (Double.isNaN(v)) return null;
        double eyes = dist(xy, 0, 1);
        if (eyes < 1e-6) return null;
        int n = LANDMARKS.length;
        double[] sig = new double[n * (n - 1) / 2 - 1];
        int k = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                if (i == 0 && j == 1) continue; // the reference distance itself
                sig[k++] = dist(xy, i, j) / eyes;
            }
        return sig;
    }

    private static double dist(double[] xy, int a, int b) {
        double dx = xy[a * 2] - xy[b * 2], dy = xy[a * 2 + 1] - xy[b * 2 + 1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Element-wise mean of several signatures (averaging frames makes enrolment steadier). */
    public static double[] average(List<double[]> sigs) {
        double[] out = null;
        int count = 0;
        for (double[] s : sigs) {
            if (s == null) continue;
            if (out == null) out = new double[s.length];
            if (s.length != out.length) continue;
            for (int i = 0; i < s.length; i++) out[i] += s[i];
            count++;
        }
        if (out == null || count == 0) return null;
        for (int i = 0; i < out.length; i++) out[i] /= count;
        return out;
    }

    /** Mean absolute difference between two signatures; +infinity if they can't be compared. */
    public static double distance(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return Double.POSITIVE_INFINITY;
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return sum / a.length;
    }

    public static boolean matches(double[] enrolled, double[] live) {
        return distance(enrolled, live) <= MATCH_THRESHOLD;
    }
}
