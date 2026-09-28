using System;
using System.Collections.Generic;
using System.Linq;
using MedAdherence.Core;
using UnityEngine;
#if UNITY_ANDROID
using UnityEngine.Android;
#endif

namespace MedAdherence
{
    /// <summary>
    /// Bridge to the native Android alarm plugin (Assets/Plugins/Android/MedAlarm.androidlib).
    /// In the Editor and on other platforms every call is a no-op and the in-app due-dose check
    /// in <see cref="MedAdherenceApp"/> plays the ringing overlay instead.
    /// </summary>
    public static class AlarmService
    {
#pragma warning disable 0649 // filled by JsonUtility
        [Serializable] class NativeEvent { public string key; public string action; public long at; }
        [Serializable] class EventList { public List<NativeEvent> items = new List<NativeEvent>(); }
        [Serializable] class NativeAlarm { public string key; public long at; public string title; public string body; public bool observed; public bool info; }
        [Serializable] class AlarmList { public List<NativeAlarm> items = new List<NativeAlarm>(); }
        [Serializable] class StringList { public List<string> items = new List<string>(); }
#pragma warning restore 0649

        public struct Event
        {
            public string doseKey;
            public DoseStatus status;
            public DateTime at;
        }

        /// <summary>Matches MedAlarmPlugin.REPEAT_MINUTES.</summary>
        public const int RepeatMinutes = 10;

        public static bool IsNative =>
#if UNITY_ANDROID && !UNITY_EDITOR
            true;
#else
            false;
#endif

#if UNITY_ANDROID && !UNITY_EDITOR
        const string PluginClass = "com.chemrob.medalarm.MedAlarmPlugin";
        static AndroidJavaClass plugin;
        static AndroidJavaClass Plugin => plugin ?? (plugin = new AndroidJavaClass(PluginClass));

        static AndroidJavaObject Activity
        {
            get
            {
#if UNITY_6000_0_OR_NEWER
                return AndroidApplication.currentActivity;
#else
                using (var up = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                    return up.GetStatic<AndroidJavaObject>("currentActivity");
#endif
            }
        }
#endif

        /// <summary>Asks for notification (Android 13+) permission. Camera is requested when observation starts.</summary>
        public static void RequestStartupPermissions()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            const string post = "android.permission.POST_NOTIFICATIONS";
            if (!Permission.HasUserAuthorizedPermission(post)) Permission.RequestUserPermission(post);
#endif
        }

        /// <summary>
        /// Re-registers alarms for every unsettled dose from now until the horizon. Called on start,
        /// on resume and whenever the regimen changes, so the alarm list is always in sync.
        /// </summary>
        public static int Reschedule(AppData data, DateTime now)
        {
            var batch = new AlarmList();
            var horizon = now.AddDays(Math.Max(1, data.settings.scheduleHorizonDays));
            // Include doses still inside the grace window so an unanswered dose keeps ringing after a reschedule.
            foreach (var dose in ScheduleEngine.Doses(data, now.AddMinutes(-data.settings.graceMinutes), horizon))
            {
                var rec = data.FindRecord(dose.Key);
                if (rec != null && (rec.Status == DoseStatus.Taken || rec.Status == DoseStatus.Skipped)) continue;

                DateTime at = dose.Time;
                if (rec != null && rec.Status == DoseStatus.Snoozed) at = rec.ActionAt.AddMinutes(data.settings.snoozeMinutes);
                if (at < now)
                {
                    // Due but unanswered and still inside the grace window: remind again shortly.
                    if (ScheduleEngine.StatusOf(data, dose, now) == DoseStatus.Missed) continue;
                    at = now.AddMinutes(RepeatMinutes);
                }

                string title = dose.Med.name + (string.IsNullOrEmpty(dose.Med.dose) ? "" : " - " + dose.Med.dose);
                string body = "Scheduled " + TimeUtil.Clock(dose.Time) +
                              (string.IsNullOrEmpty(dose.Med.instructions) ? "" : " | " + dose.Med.instructions);
                batch.items.Add(new NativeAlarm { key = dose.Key, at = ToEpochMs(at), title = title, body = body, observed = dose.Med.observed });
            }
            // Alarms only exist up to the horizon, and the app cannot extend them without being opened.
            // Two days before the last one, post a plain notice asking the patient to open the app.
            if (batch.items.Count > 0)
            {
                var notice = horizon.Date.AddDays(-2).AddHours(10);
                if (notice > now)
                    batch.items.Add(new NativeAlarm
                    {
                        key = DoseKey.Make("REFRESH", notice), at = ToEpochMs(notice), info = true,
                        title = "Open MedAdherence to keep your reminders",
                        body = "Medicine alarms are set until " + horizon.ToString("d MMM") +
                               ". Opening the app sets the next " + data.settings.scheduleHorizonDays + " days.",
                    });
            }
#if UNITY_ANDROID && !UNITY_EDITOR
            Plugin.CallStatic("scheduleAll", Activity, JsonUtility.ToJson(batch));
#endif
            return batch.items.Count(a => !a.info);
        }

        static long ToEpochMs(DateTime local) =>
            new DateTimeOffset(DateTime.SpecifyKind(local, DateTimeKind.Local)).ToUnixTimeMilliseconds();

        public static void Schedule(string doseKey, DateTime localTime, string title, string body, bool observed)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Plugin.CallStatic("schedule", Activity, doseKey, ToEpochMs(localTime), title, body, observed);
#endif
        }

        /// <summary>Stops ringing and removes all pending alarms for a dose that has been recorded.</summary>
        public static void Cancel(string doseKey)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Plugin.CallStatic("cancel", Activity, doseKey);
#endif
        }

        public static void Silence(string doseKey)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Plugin.CallStatic("silence", Activity, doseKey);
#endif
        }

        /// <summary>Actions the patient took from the notification or ringing screen while the app was closed.</summary>
        public static List<Event> DrainEvents()
        {
            var result = new List<Event>();
#if UNITY_ANDROID && !UNITY_EDITOR
            string json = Plugin.CallStatic<string>("drainEvents", Activity);
            var list = JsonUtility.FromJson<EventList>("{\"items\":" + (string.IsNullOrEmpty(json) ? "[]" : json) + "}");
            foreach (var e in list.items)
            {
                DoseStatus s = e.action == "taken" ? DoseStatus.Taken : e.action == "skip" ? DoseStatus.Skipped : DoseStatus.Snoozed;
                result.Add(new Event
                {
                    doseKey = e.key,
                    status = s,
                    at = DateTimeOffset.FromUnixTimeMilliseconds(e.at).LocalDateTime,
                });
            }
#endif
            return result;
        }

        public static List<string> Ringing()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            string json = Plugin.CallStatic<string>("getRinging", Activity);
            return JsonUtility.FromJson<StringList>("{\"items\":" + (string.IsNullOrEmpty(json) ? "[]" : json) + "}").items;
#else
            return new List<string>();
#endif
        }

        /// <summary>Dose the patient chose to "Take on camera" from the ringing screen, or null.</summary>
        public static string ConsumeLaunchDose()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            string s = Plugin.CallStatic<string>("consumeLaunchDose", Activity);
            return string.IsNullOrEmpty(s) ? null : s;
#else
            return null;
#endif
        }

        public static void ShowOverLockScreen(bool show)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Plugin.CallStatic("setShowOverLockScreen", Activity, show);
#endif
        }

        public static void ShareText(string subject, string text)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Plugin.CallStatic("shareText", Activity, subject, text);
#else
            GUIUtility.systemCopyBuffer = text;
            Debug.Log("[MedAdherence] Share (copied to clipboard):\n" + text);
#endif
        }

        /// <summary>Reliability checks shown on the settings screen: (ok, label, fix action).</summary>
        public static List<(bool ok, string label, Action fix)> ReliabilityChecks()
        {
            var list = new List<(bool, string, Action)>();
#if UNITY_ANDROID && !UNITY_EDITOR
            var act = Activity;
            list.Add((Permission.HasUserAuthorizedPermission("android.permission.POST_NOTIFICATIONS"), "Notifications allowed",
                () => Permission.RequestUserPermission("android.permission.POST_NOTIFICATIONS")));
            list.Add((Plugin.CallStatic<bool>("canScheduleExact", act), "Exact alarm time allowed",
                () => Plugin.CallStatic("openExactAlarmSettings", Activity)));
            list.Add((Plugin.CallStatic<bool>("canUseFullScreenIntent", act), "Ring over lock screen allowed",
                () => Plugin.CallStatic("openFullScreenIntentSettings", Activity)));
            list.Add((Plugin.CallStatic<bool>("isIgnoringBatteryOptimizations", act), "Battery optimisation off",
                () => Plugin.CallStatic("openBatterySettings", Activity)));
            list.Add((Permission.HasUserAuthorizedPermission(Permission.Camera), "Camera allowed (observed doses)",
                () => Permission.RequestUserPermission(Permission.Camera)));
#else
            list.Add((true, "Editor mode: alarms are simulated in-app", null));
#endif
            return list;
        }
    }
}
