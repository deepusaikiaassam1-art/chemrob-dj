package com.chemrob.medadherence.core;

/**
 * Cuts the aligned 112x112 face straight out of a camera NV21 frame, in one pass: output pixel
 * -> aligned-face transform (inverse) -> upright image -> camera sensor position, sampled with
 * bilinear luma. No Bitmaps, so it is fast and testable.
 */
public final class FaceCrop {
    private FaceCrop() {}

    /**
     * Maps a point of the upright image back to the sensor frame.
     * @param rotation degrees the sensor frame is turned clockwise to be upright (0/90/180/270)
     */
    public static double[] uprightToSensor(double x, double y, int w, int h, int rotation) {
        switch (((rotation % 360) + 360) % 360) {
            case 90:  return new double[]{y, h - 1 - x};
            case 180: return new double[]{w - 1 - x, h - 1 - y};
            case 270: return new double[]{w - 1 - y, x};
            default:  return new double[]{x, y};
        }
    }

    /**
     * @param t aligned-face transform from {@link FaceMatch#alignTransform} (upright image -> 112x112)
     * @return RGB floats 0-255, row-major HWC, 112*112*3 values; null if t is not invertible
     */
    public static float[] alignedRgb(byte[] nv21, int w, int h, int rotation, double[] t) {
        double a = t[0], b = t[1], tx = t[2], c = t[3], d = t[4], ty = t[5];
        double det = a * d - b * c;
        if (Math.abs(det) < 1e-12) return null;
        // Inverse: upright = M^-1 (out - T)
        double ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        int n = FaceMatch.SIZE;
        float[] out = new float[n * n * 3];
        int k = 0;
        for (int v = 0; v < n; v++) {
            for (int u = 0; u < n; u++) {
                double ox = u - tx, oy = v - ty;
                double ux = ia * ox + ib * oy, uy = ic * ox + id * oy;
                double[] s = uprightToSensor(ux, uy, w, h, rotation);
                double sx = s[0], sy = s[1];
                if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) { k += 3; continue; } // black outside
                int x0 = (int) sx, y0 = (int) sy;
                int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
                double fx = sx - x0, fy = sy - y0;
                double yv = (1 - fy) * ((1 - fx) * lum(nv21, x0, y0, w) + fx * lum(nv21, x1, y0, w))
                        + fy * ((1 - fx) * lum(nv21, x0, y1, w) + fx * lum(nv21, x1, y1, w));
                int uv = w * h + (y0 >> 1) * w + (x0 & ~1);
                double vv = uv + 1 < nv21.length ? (nv21[uv] & 0xff) - 128 : 0;
                double uu = uv + 1 < nv21.length ? (nv21[uv + 1] & 0xff) - 128 : 0;
                out[k++] = clamp(yv + 1.402 * vv);
                out[k++] = clamp(yv - 0.344136 * uu - 0.714136 * vv);
                out[k++] = clamp(yv + 1.772 * uu);
            }
        }
        return out;
    }

    private static int lum(byte[] nv21, int x, int y, int w) { return nv21[y * w + x] & 0xff; }

    private static float clamp(double v) { return (float) (v < 0 ? 0 : v > 255 ? 255 : v); }
}
