using System;

namespace MedAdherence.Core
{
    /// <summary>
    /// Lightweight, model-free checks on camera frames used by the observed-dose mode.
    /// Frames are RGBA32 byte arrays (4 bytes per pixel, row-major), usually a downsampled copy.
    ///  - Brightness: rejects a covered or pitch-dark camera.
    ///  - Motion: mean absolute luma difference between frames; a live person moves, a photo held
    ///    up to the camera does not.
    ///  - Skin presence: share of skin-toned pixels (YCbCr rule) in the centre of the frame, a cheap
    ///    stand-in for "a face / hand is in view".
    /// These are heuristics that decide between "auto-verified" and "needs pharmacist review";
    /// every step's snapshot is kept as evidence either way.
    /// </summary>
    public static class FrameAnalysis
    {
        public static float MeanLuma(byte[] rgba)
        {
            if (rgba == null || rgba.Length < 4) return 0f;
            long sum = 0;
            int n = rgba.Length / 4;
            for (int i = 0; i < n; i++) sum += Luma(rgba, i * 4);
            return sum / (255f * n);
        }

        /// <summary>Mean absolute luma difference in [0,1]. Returns 0 if sizes differ.</summary>
        public static float Motion(byte[] a, byte[] b)
        {
            if (a == null || b == null || a.Length != b.Length || a.Length < 4) return 0f;
            long sum = 0;
            int n = a.Length / 4;
            for (int i = 0; i < n; i++) sum += Math.Abs(Luma(a, i * 4) - Luma(b, i * 4));
            return sum / (255f * n);
        }

        /// <summary>Fraction of skin-toned pixels inside the central box covering <paramref name="centreFraction"/> of each axis.</summary>
        public static float SkinRatio(byte[] rgba, int width, int height, float centreFraction = 0.6f)
        {
            if (rgba == null || width <= 0 || height <= 0 || rgba.Length < width * height * 4) return 0f;
            int x0 = (int)(width * (1 - centreFraction) / 2), x1 = width - x0;
            int y0 = (int)(height * (1 - centreFraction) / 2), y1 = height - y0;
            int skin = 0, total = 0;
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++)
                {
                    int o = (y * width + x) * 4;
                    if (IsSkin(rgba[o], rgba[o + 1], rgba[o + 2])) skin++;
                    total++;
                }
            return total == 0 ? 0f : (float)skin / total;
        }

        /// <summary>Chai &amp; Ngan YCbCr skin rule, with a minimum-brightness guard.</summary>
        public static bool IsSkin(byte r, byte g, byte b)
        {
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            float cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b;
            float cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b;
            return y > 40 && cb >= 77 && cb <= 127 && cr >= 133 && cr <= 173;
        }

        /// <summary>
        /// Rotates an RGBA image (bottom-left origin, as returned by Unity's GetPixels32) clockwise
        /// by 0/90/180/270 degrees, e.g. by WebCamTexture.videoRotationAngle to make it upright.
        /// </summary>
        public static byte[] RotateClockwise(byte[] src, int w, int h, int degrees, out int outW, out int outH)
        {
            int d = ((degrees % 360) + 360) % 360;
            bool swap = d == 90 || d == 270;
            outW = swap ? h : w;
            outH = swap ? w : h;
            if (d != 0 && d != 90 && d != 180 && d != 270) throw new ArgumentException("degrees must be a multiple of 90");
            var dst = new byte[src.Length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                {
                    int nx, ny;
                    switch (d)
                    {
                        case 90: nx = y; ny = w - 1 - x; break;
                        case 180: nx = w - 1 - x; ny = h - 1 - y; break;
                        case 270: nx = h - 1 - y; ny = x; break;
                        default: nx = x; ny = y; break;
                    }
                    Buffer.BlockCopy(src, (y * w + x) * 4, dst, (ny * outW + nx) * 4, 4);
                }
            return dst;
        }

        static int Luma(byte[] p, int o) => (p[o] * 77 + p[o + 1] * 150 + p[o + 2] * 29) >> 8;
    }

    /// <summary>Per-step thresholds and scoring for an observation session.</summary>
    public class ObservationCriteria
    {
        public float minBrightness = 0.12f;
        public float minMotion = 0.02f;   // peak frame-to-frame change needed during a step
        public float minSkin = 0.08f;     // peak skin ratio needed during a step

        public bool StepPassed(float brightness, float peakMotion, float peakSkin) =>
            brightness >= minBrightness && peakMotion >= minMotion && peakSkin >= minSkin;

        /// <summary>Auto-verify only when every step passed; otherwise the pharmacist reviews the photos.</summary>
        public VerificationStatus Verdict(int stepsPassed, int stepsTotal, bool completed) =>
            completed && stepsTotal > 0 && stepsPassed == stepsTotal ? VerificationStatus.AutoVerified : VerificationStatus.NeedsReview;
    }
}
