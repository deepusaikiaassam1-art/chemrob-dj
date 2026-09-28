package com.chemrob.medadherence.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class FaceCropTest {
    /** Grey NV21 frame whose luma is x + 2y (neutral chroma). */
    static byte[] ramp(int w, int h) {
        byte[] f = new byte[w * h * 3 / 2];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) f[y * w + x] = (byte) Math.min(255, x + 2 * y);
        for (int i = w * h; i < f.length; i++) f[i] = (byte) 128;
        return f;
    }

    @Test public void rotationMapping() {
        int w = 640, h = 480;
        // Rotated 90 clockwise: sensor top-left ends up at the upright top-right.
        assertArrayEquals(new double[]{0, 0}, FaceCrop.uprightToSensor(h - 1, 0, w, h, 90), 1e-9);
        // Rotated 270 clockwise: sensor top-left ends up at the upright bottom-left.
        assertArrayEquals(new double[]{0, 0}, FaceCrop.uprightToSensor(0, w - 1, w, h, 270), 1e-9);
        assertArrayEquals(new double[]{w - 1, h - 1}, FaceCrop.uprightToSensor(0, 0, w, h, 180), 1e-9);
        assertArrayEquals(new double[]{5, 7}, FaceCrop.uprightToSensor(5, 7, w, h, 0), 1e-9);
    }

    @Test public void cropSamplesTheRightPixels() {
        int w = 160, h = 120;
        byte[] f = ramp(w, h);
        // Identity transform, no rotation: output pixel (u,v) = sensor (u,v).
        float[] rgb = FaceCrop.alignedRgb(f, w, h, 0, new double[]{1, 0, 0, 0, 1, 0});
        assertEquals(FaceMatch.SIZE * FaceMatch.SIZE * 3, rgb.length);
        int u = 30, v = 20, i = (v * FaceMatch.SIZE + u) * 3;
        assertEquals(u + 2 * v, rgb[i], 1e-3);
        assertEquals(rgb[i], rgb[i + 1], 1e-3); // grey: R = G = B
        assertEquals(rgb[i], rgb[i + 2], 1e-3);

        // Half scale with offset: out = 0.5 * upright + 10  ->  upright = 2 * (out - 10).
        rgb = FaceCrop.alignedRgb(f, w, h, 0, new double[]{0.5, 0, 10, 0, 0.5, 10});
        i = (20 * FaceMatch.SIZE + 30) * 3;
        assertEquals(40 + 2 * 20, rgb[i], 1e-3);
        // Outside the frame is black.
        assertEquals(0, rgb[0], 0);

        // Rotation 90: upright (x,y) = sensor (y, h-1-x).
        rgb = FaceCrop.alignedRgb(f, w, h, 90, new double[]{1, 0, 0, 0, 1, 0});
        i = (20 * FaceMatch.SIZE + 30) * 3;
        assertEquals(20 + 2 * (h - 1 - 30), rgb[i], 1e-3);

        assertNull(FaceCrop.alignedRgb(f, w, h, 0, new double[6]));
    }
}
