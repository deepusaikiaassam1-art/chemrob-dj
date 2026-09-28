package com.chemrob.medadherence.core;

import java.util.List;

/**
 * Face recognition maths (model-independent): face alignment to the standard 112x112 template,
 * and comparison of face embeddings ("face fingerprints") by cosine similarity.
 *
 * The embeddings come from SFace (MobileFaceNet trained with the SFace loss, OpenCV model zoo,
 * Apache 2.0). On test photos the same person scored 0.65-0.93 and different people at most 0.27;
 * OpenCV's reference threshold is 0.363. We use 0.40 and compare against several enrolled views.
 */
public final class FaceMatch {
    private FaceMatch() {}

    /** Similarity at or above this = same person. */
    public static final double THRESHOLD = 0.40;
    public static final int SIZE = 112;

    /**
     * Destination template (ArcFace / SFace, 112x112): image-left eye, image-right eye, nose,
     * image-left mouth corner, image-right mouth corner.
     */
    public static final double[] TEMPLATE = {
            38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041};

    /**
     * Least-squares similarity transform (rotation, uniform scale, translation) that maps the five
     * source landmarks onto {@link #TEMPLATE} (Umeyama's method).
     * @param src x,y pairs in template order (10 numbers)
     * @return {a, b, tx, c, d, ty} with x' = a*x + b*y + tx, y' = c*x + d*y + ty; null if degenerate
     */
    public static double[] alignTransform(double[] src) {
        if (src == null || src.length != 10) return null;
        int n = 5;
        double msx = 0, msy = 0, mdx = 0, mdy = 0;
        for (int i = 0; i < n; i++) {
            msx += src[2 * i]; msy += src[2 * i + 1];
            mdx += TEMPLATE[2 * i]; mdy += TEMPLATE[2 * i + 1];
        }
        msx /= n; msy /= n; mdx /= n; mdy /= n;
        // Covariance (dst x src) and source variance.
        double c00 = 0, c01 = 0, c10 = 0, c11 = 0, var = 0;
        for (int i = 0; i < n; i++) {
            double sx = src[2 * i] - msx, sy = src[2 * i + 1] - msy;
            double dx = TEMPLATE[2 * i] - mdx, dy = TEMPLATE[2 * i + 1] - mdy;
            c00 += dx * sx; c01 += dx * sy; c10 += dy * sx; c11 += dy * sy;
            var += sx * sx + sy * sy;
        }
        if (var < 1e-9) return null;
        // For 2-D similarity: rotation angle and scale have a closed form.
        double p = c00 + c11, q = c10 - c01;
        double norm = Math.hypot(p, q);
        if (norm < 1e-12) return null;
        double cos = p / norm, sin = q / norm;
        double scale = norm / var;
        double a = scale * cos, b = -scale * sin, c = scale * sin, d = scale * cos;
        double tx = mdx - (a * msx + b * msy), ty = mdy - (c * msx + d * msy);
        return new double[]{a, b, tx, c, d, ty};
    }

    /**
     * Puts five detected landmarks into template order, using image positions rather than
     * left/right names (so it works whether or not the image is mirrored).
     * @return 10 numbers, or null if any point is missing
     */
    public static double[] templateOrder(double[] eyeA, double[] eyeB, double[] nose, double[] mouthA, double[] mouthB) {
        if (eyeA == null || eyeB == null || nose == null || mouthA == null || mouthB == null) return null;
        double[] le = eyeA[0] <= eyeB[0] ? eyeA : eyeB, re = le == eyeA ? eyeB : eyeA;
        double[] lm = mouthA[0] <= mouthB[0] ? mouthA : mouthB, rm = lm == mouthA ? mouthB : mouthA;
        return new double[]{le[0], le[1], re[0], re[1], nose[0], nose[1], lm[0], lm[1], rm[0], rm[1]};
    }

    public static float[] normalize(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        double n = Math.sqrt(s);
        float[] out = new float[v.length];
        if (n < 1e-12) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return Double.NaN;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? Double.NaN : dot / Math.sqrt(na * nb);
    }

    /** Best similarity of a live embedding against all enrolled views; NaN if nothing to compare. */
    public static double best(List<float[]> enrolled, float[] live) {
        double best = Double.NaN;
        if (enrolled == null) return best;
        for (float[] e : enrolled) {
            double c = cosine(e, live);
            if (!Double.isNaN(c) && (Double.isNaN(best) || c > best)) best = c;
        }
        return best;
    }

    public static boolean isMatch(double similarity) { return !Double.isNaN(similarity) && similarity >= THRESHOLD; }

    /**
     * Enrolment quality: every view must look like at least one other view (a bad frame or a second
     * person during enrolment pulls this down, while a left and a right head turn of the same person
     * may differ more from each other than from the straight views). Returns the lowest, over all
     * views, of that view's best similarity to the others; NaN with fewer than two views.
     */
    public static double consistency(List<float[]> views) {
        if (views.size() < 2) return Double.NaN;
        double worst = 1;
        for (int i = 0; i < views.size(); i++) {
            double best = -1;
            for (int j = 0; j < views.size(); j++) if (i != j) best = Math.max(best, cosine(views.get(i), views.get(j)));
            worst = Math.min(worst, best);
        }
        return worst;
    }

    /** Enrolled views must agree at least this well. */
    public static final double MIN_ENROL_CONSISTENCY = 0.45;
}
