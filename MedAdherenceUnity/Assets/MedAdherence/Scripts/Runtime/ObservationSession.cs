using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using MedAdherence.Core;
using MedAdherence.UI;
using UnityEngine;
using UnityEngine.UI;
#if UNITY_ANDROID
using UnityEngine.Android;
#endif

namespace MedAdherence
{
    public class ObservationResult
    {
        public bool completed;
        public int stepsPassed, stepsTotal;
        public float livenessScore, presenceScore;
        public VerificationStatus verdict = VerificationStatus.NeedsReview;
        public List<string> evidence = new List<string>();
    }

    /// <summary>
    /// "Special detection" mode: directly observed therapy through the front camera.
    /// The patient is guided through the intake steps; for each step the camera stream is checked
    /// for light, a person in view and live movement, and a timestamped snapshot is saved as evidence.
    /// All steps passing = auto-verified; otherwise the dose is queued for pharmacist review.
    /// </summary>
    public class ObservationSession : MonoBehaviour
    {
        static readonly string[] Steps =
        {
            "Look at the camera so your face is in the frame",
            "Hold the medicine up to the camera",
            "Put the medicine in your mouth",
            "Drink water and swallow",
            "Open your mouth to show it is empty",
        };

        const float StepSeconds = 6f;
        const float SampleInterval = 0.2f;
        const int AnalysisWidth = 64;       // frames are downsampled to this width for the checks
        const int SnapshotMaxSide = 720;

        readonly ObservationCriteria criteria = new ObservationCriteria();

        WebCamTexture cam;
        RawImage preview;
        Text instruction, status, counter;
        Action<ObservationResult> onDone;
        bool cancelled;
        Medication med;
        string doseKey;

        public static ObservationSession Begin(Transform overlayParent, Medication med, string doseKey, Action<ObservationResult> done)
        {
            var root = UIKit.Panel(overlayParent, Color.black, false, "Observation");
            UIKit.Stretch(root.rectTransform);
            var s = root.gameObject.AddComponent<ObservationSession>();
            s.med = med;
            s.doseKey = doseKey;
            s.onDone = done;
            s.BuildUi();
            s.StartCoroutine(s.Run());
            return s;
        }

        void BuildUi()
        {
            preview = UIKit.Rect(transform, "Preview").gameObject.AddComponent<RawImage>();
            preview.color = Color.white;

            var top = UIKit.Panel(transform, new Color(0, 0, 0, 0.6f), false, "Top");
            var trt = top.rectTransform;
            trt.anchorMin = new Vector2(0, 1); trt.anchorMax = Vector2.one; trt.pivot = new Vector2(0.5f, 1);
            trt.sizeDelta = new Vector2(0, 420);
            var tv = top.gameObject.AddComponent<VerticalLayoutGroup>();
            UIKit.Configure(tv, 12, 40);
            UIKit.Label(top.transform, "Observed dose: " + med.name + " " + med.dose, 38, UIKit.Hex("9FD8EC"), FontStyle.Bold);
            counter = UIKit.Label(top.transform, "", 34, Color.white);
            instruction = UIKit.Label(top.transform, "Starting camera...", 54, Color.white, FontStyle.Bold);
            status = UIKit.Label(top.transform, "", 34, Color.white);

            var cancel = UIKit.Button(transform, "Cancel", UIKit.Bad, () => { cancelled = true; }, 120);
            var crt = (RectTransform)cancel.transform;
            crt.anchorMin = new Vector2(0.25f, 0); crt.anchorMax = new Vector2(0.75f, 0); crt.pivot = new Vector2(0.5f, 0);
            crt.anchoredPosition = new Vector2(0, 80); crt.sizeDelta = new Vector2(0, 120);
        }

        IEnumerator Run()
        {
            AlarmService.Silence(doseKey);
            var result = new ObservationResult { stepsTotal = Steps.Length };

#if UNITY_ANDROID && !UNITY_EDITOR
            if (!Permission.HasUserAuthorizedPermission(Permission.Camera))
            {
                Permission.RequestUserPermission(Permission.Camera);
                float wait = 0;
                while (!Permission.HasUserAuthorizedPermission(Permission.Camera) && wait < 30 && !cancelled)
                { wait += Time.unscaledDeltaTime; yield return null; }
            }
#else
            yield return Application.RequestUserAuthorization(UserAuthorization.WebCam);
#endif
            if (WebCamTexture.devices.Length == 0 || cancelled)
            {
                yield return Finish(result, cancelled ? null : "No camera available on this device.");
                yield break;
            }

            string device = WebCamTexture.devices[0].name;
            foreach (var d in WebCamTexture.devices) if (d.isFrontFacing) { device = d.name; break; }
            cam = new WebCamTexture(device, 1280, 720, 30);
            cam.Play();
            float t0 = Time.unscaledTime;
            while (cam.width < 32 && Time.unscaledTime - t0 < 5f) yield return null; // wait for real frames
            preview.texture = cam;

            string dir = Path.Combine(DataStore.EvidenceDir, doseKey.Replace('|', '_'));
            Directory.CreateDirectory(dir);
            float livenessSum = 0, presenceSum = 0;

            for (int i = 0; i < Steps.Length && !cancelled; i++)
            {
                instruction.text = Steps[i];
                counter.text = "Step " + (i + 1) + " of " + Steps.Length;
                byte[] prev = null;
                float peakMotion = 0, peakSkin = 0, brightSum = 0;
                int samples = 0;
                float stepStart = Time.unscaledTime, nextSample = 0;

                while (Time.unscaledTime - stepStart < StepSeconds && !cancelled)
                {
                    FitPreview();
                    if (Time.unscaledTime >= nextSample && cam.didUpdateThisFrame)
                    {
                        nextSample = Time.unscaledTime + SampleInterval;
                        var small = Downsample(cam.GetPixels32(), cam.width, cam.height, AnalysisWidth, out int sw, out int sh);
                        brightSum += FrameAnalysis.MeanLuma(small);
                        samples++;
                        if (prev != null) peakMotion = Mathf.Max(peakMotion, FrameAnalysis.Motion(prev, small));
                        peakSkin = Mathf.Max(peakSkin, FrameAnalysis.SkinRatio(small, sw, sh));
                        prev = small;
                    }
                    float left = StepSeconds - (Time.unscaledTime - stepStart);
                    status.text = string.Format("{0}  person in view   {1}  movement   {2}  light      {3:0}s",
                        Tick(peakSkin >= criteria.minSkin), Tick(peakMotion >= criteria.minMotion),
                        Tick(samples > 0 && brightSum / samples >= criteria.minBrightness), Mathf.Ceil(left));
                    yield return null;
                }
                if (cancelled) break;

                float bright = samples > 0 ? brightSum / samples : 0;
                bool passed = criteria.StepPassed(bright, peakMotion, peakSkin);
                if (passed) result.stepsPassed++;
                livenessSum += Mathf.Clamp01(peakMotion / criteria.minMotion);
                presenceSum += Mathf.Clamp01(peakSkin / criteria.minSkin);

                string file = SaveSnapshot(dir, i + 1);
                if (file != null) result.evidence.Add(file);
            }

            result.completed = !cancelled;
            result.livenessScore = livenessSum / Steps.Length;
            result.presenceScore = presenceSum / Steps.Length;
            result.verdict = criteria.Verdict(result.stepsPassed, result.stepsTotal, result.completed);
            yield return Finish(result, null);
        }

        IEnumerator Finish(ObservationResult result, string error)
        {
            if (cam != null) cam.Stop();
            if (error != null)
            {
                instruction.text = error;
                yield return new WaitForSecondsRealtime(2.5f);
            }
            else if (result.completed)
            {
                instruction.text = result.verdict == VerificationStatus.AutoVerified
                    ? "Dose verified. Well done!"
                    : "Dose recorded. Your pharmacist will review the photos.";
                status.text = string.Format("{0}/{1} steps confirmed", result.stepsPassed, result.stepsTotal);
                yield return new WaitForSecondsRealtime(2.5f);
            }
            onDone?.Invoke(result);
            Destroy(gameObject);
        }

        void OnDestroy()
        {
            if (cam != null) { cam.Stop(); Destroy(cam); }
        }

        static string Tick(bool ok) => ok ? "<color=#5FD37A><b>[OK]</b></color>" : "<color=#FFB84D>[ .. ]</color>";

        /// <summary>Rotates, mirrors and scales the camera image so it fills the screen upright, selfie-style.</summary>
        void FitPreview()
        {
            if (cam == null || cam.width < 32) return;
            int angle = cam.videoRotationAngle;
            bool sideways = angle % 180 != 0;
            var parent = (RectTransform)preview.transform.parent;
            float pw = parent.rect.width, ph = parent.rect.height;
            float shown = sideways ? (float)cam.height / cam.width : (float)cam.width / cam.height; // on-screen aspect
            float dh = Mathf.Max(ph, pw / shown), dw = dh * shown;                                  // cover the screen

            var rt = preview.rectTransform;
            rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = sideways ? new Vector2(dh, dw) : new Vector2(dw, dh);
            rt.localEulerAngles = new Vector3(0, 0, -angle);
            // Selfie mirror is a horizontal flip on screen, which is the local Y axis once rotated 90 degrees.
            rt.localScale = sideways ? new Vector3(1, -1, 1) : new Vector3(-1, 1, 1);
            preview.uvRect = cam.videoVerticallyMirrored ? new Rect(0, 1, 1, -1) : new Rect(0, 0, 1, 1);
        }

        /// <summary>Nearest-neighbour downsample of a Color32 frame to RGBA bytes.</summary>
        static byte[] Downsample(Color32[] px, int w, int h, int targetW, out int outW, out int outH)
        {
            int step = Mathf.Max(1, w / targetW);
            outW = w / step;
            outH = h / step;
            var bytes = new byte[outW * outH * 4];
            int o = 0;
            for (int y = 0; y < outH; y++)
                for (int x = 0; x < outW; x++)
                {
                    var c = px[(y * step) * w + x * step];
                    bytes[o++] = c.r; bytes[o++] = c.g; bytes[o++] = c.b; bytes[o++] = 255;
                }
            return bytes;
        }

        string SaveSnapshot(string dir, int step)
        {
            try
            {
                int longest = Mathf.Max(cam.width, cam.height);
                int targetW = Mathf.Max(1, cam.width * SnapshotMaxSide / longest);
                var bytes = Downsample(cam.GetPixels32(), cam.width, cam.height, targetW, out int w, out int h);
                bytes = FrameAnalysis.RotateClockwise(bytes, w, h, cam.videoRotationAngle, out w, out h);
                var tex = new Texture2D(w, h, TextureFormat.RGBA32, false);
                tex.LoadRawTextureData(bytes);
                tex.Apply();
                byte[] jpg = tex.EncodeToJPG(80);
                Destroy(tex);
                string path = Path.Combine(dir, string.Format("step{0}_{1:HHmmss}.jpg", step, DateTime.Now));
                File.WriteAllBytes(path, jpg);
                return path;
            }
            catch (Exception e)
            {
                Debug.LogWarning("[MedAdherence] Snapshot failed: " + e.Message);
                return null;
            }
        }
    }
}
