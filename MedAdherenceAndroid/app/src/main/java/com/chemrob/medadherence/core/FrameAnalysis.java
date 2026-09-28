package com.chemrob.medadherence.core;

/**
 * Lightweight, model-free checks on camera preview frames (NV21, the Android camera default:
 * a full-resolution Y plane followed by interleaved V/U at half resolution).
 *  - Brightness: rejects a covered or dark camera.
 *  - Motion: mean absolute luma change between frames; a live person moves, a held-up photo does not.
 *  - Skin presence: share of skin-toned chroma samples in the centre of the frame (YCbCr rule),
 *    a cheap stand-in for "a face or hand is in view".
 * These decide between "auto-verified" and "needs pharmacist review"; photo evidence is kept either way.
 */
public final class FrameAnalysis {
    private FrameAnalysis() {}

    /** Downsampled luma (step = sampling stride) for cheap motion comparison. */
    public static byte[] sampleLuma(byte[] nv21, int w, int h, int step) {
        int sw = w / step, sh = h / step;
        byte[] out = new byte[sw * sh];
        int o = 0;
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) out[o++] = nv21[(y * step) * w + x * step];
        return out;
    }

    public static double meanLuma(byte[] luma) {
        if (luma == null || luma.length == 0) return 0;
        long sum = 0;
        for (byte b : luma) sum += b & 0xFF;
        return sum / (255.0 * luma.length);
    }

    /** Mean absolute luma difference in [0,1]; 0 when sizes differ. */
    public static double motion(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        return sum / (255.0 * a.length);
    }

    /** Fraction of skin-toned samples in the central box covering {@code centre} of each axis. */
    public static double skinRatioNv21(byte[] nv21, int w, int h, double centre) {
        if (nv21 == null || nv21.length < w * h * 3 / 2) return 0;
        int cw = w / 2, ch = h / 2, base = w * h;
        int x0 = (int) (cw * (1 - centre) / 2), x1 = cw - x0;
        int y0 = (int) (ch * (1 - centre) / 2), y1 = ch - y0;
        int skin = 0, total = 0;
        for (int y = y0; y < y1; y += 2)
            for (int x = x0; x < x1; x += 2) {
                int o = base + y * w + x * 2;
                int cr = nv21[o] & 0xFF, cb = nv21[o + 1] & 0xFF; // NV21 stores V (Cr) before U (Cb)
                int luma = nv21[(y * 2) * w + x * 2] & 0xFF;
                if (isSkinYCbCr(luma, cb, cr)) skin++;
                total++;
            }
        return total == 0 ? 0 : (double) skin / total;
    }

    /** Chai and Ngan skin rule with a minimum-brightness guard. */
    public static boolean isSkinYCbCr(int y, int cb, int cr) {
        return y > 40 && cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173;
    }

    public static boolean isSkinRgb(int r, int g, int b) {
        double y = 0.299 * r + 0.587 * g + 0.114 * b;
        double cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
        double cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
        return isSkinYCbCr((int) y, (int) Math.round(cb), (int) Math.round(cr));
    }

    /** Per-step thresholds for an observation session. */
    public static final class Criteria {
        public double minBrightness = 0.12;
        public double minMotion = 0.02;
        public double minSkin = 0.08;

        public boolean stepPassed(double brightness, double peakMotion, double peakSkin) {
            return brightness >= minBrightness && peakMotion >= minMotion && peakSkin >= minSkin;
        }

        /** Auto-verify only when every step passed; otherwise the pharmacist reviews the photos. */
        public Verification verdict(int passed, int total, boolean completed) {
            return completed && total > 0 && passed == total ? Verification.AUTO_VERIFIED : Verification.NEEDS_REVIEW;
        }
    }
}
