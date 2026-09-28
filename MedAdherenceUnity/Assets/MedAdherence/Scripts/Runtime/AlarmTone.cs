using UnityEngine;

namespace MedAdherence
{
    /// <summary>
    /// In-app ringing for when the app is open (and in the Editor, where there is no native alarm):
    /// a generated two-tone alarm loop plus periodic vibration. No audio asset is needed.
    /// </summary>
    public class AlarmTone : MonoBehaviour
    {
        AudioSource source;
        float nextVibrate;

        void Awake()
        {
            source = gameObject.AddComponent<AudioSource>();
            source.clip = MakeClip();
            source.loop = true;
            source.playOnAwake = false;
        }

        public bool IsPlaying => source != null && source.isPlaying;

        public void Play()
        {
            if (!source.isPlaying) source.Play();
            nextVibrate = 0;
        }

        public void Stop() => source.Stop();

        void Update()
        {
            if (!source.isPlaying || Time.unscaledTime < nextVibrate) return;
            nextVibrate = Time.unscaledTime + 1.5f;
#if UNITY_ANDROID || UNITY_IOS
            Handheld.Vibrate();
#endif
        }

        static AudioClip MakeClip()
        {
            const int rate = 22050;
            const float seconds = 1.6f;
            int n = (int)(rate * seconds);
            var data = new float[n];
            for (int i = 0; i < n; i++)
            {
                float t = (float)i / rate;
                float phase = t % 0.8f;                 // beep-beep pattern, 0.8 s period
                bool on = phase < 0.18f || (phase > 0.28f && phase < 0.46f);
                float f = t < 0.8f ? 880f : 988f;       // alternate A5 / B5
                float env = on ? 0.6f : 0f;
                data[i] = env * Mathf.Sin(2 * Mathf.PI * f * t);
            }
            var clip = AudioClip.Create("MedAlarmTone", n, 1, rate, false);
            clip.SetData(data, 0);
            return clip;
        }
    }
}
