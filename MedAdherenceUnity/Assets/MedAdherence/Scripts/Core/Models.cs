using System;
using System.Collections.Generic;
using System.Globalization;

namespace MedAdherence.Core
{
    /// <summary>What happened to one scheduled dose.</summary>
    public enum DoseStatus
    {
        Pending = 0,   // not yet due, or inside the grace window with no action
        Taken = 1,
        Skipped = 2,   // patient explicitly declined / held the dose
        Missed = 3,    // grace window passed with no action (derived, never stored)
        Snoozed = 4,
    }

    /// <summary>Outcome of a directly-observed dose.</summary>
    public enum VerificationStatus
    {
        NotRequired = 0,
        AutoVerified = 1,      // on-device checks passed
        NeedsReview = 2,       // evidence captured, checks inconclusive
        PharmacistApproved = 3,
        PharmacistRejected = 4,
    }

    /// <summary>One drug in the patient's regimen.</summary>
    [Serializable]
    public class Medication
    {
        public string id = Guid.NewGuid().ToString("N");
        public string name = "";
        public string dose = "";            // free text, e.g. "500 mg, 1 tablet"
        public string instructions = "";    // e.g. "after food"
        public List<string> times = new List<string>(); // local clock times "HH:mm"
        public string startDate = "";       // "yyyy-MM-dd"
        public int durationDays;            // 0 = ongoing
        public int everyNDays = 1;          // 1 = daily, 7 = weekly
        public bool observed;               // requires camera-observed intake
        public List<string> pauses = new List<string>(); // "yyyy-MM-dd HH:mm|yyyy-MM-dd HH:mm", open-ended if nothing after '|'
        public string addedBy = "patient";  // "patient" | "pharmacist"
        public float stock = -1;            // units on hand (tablets, capsules, ml...); -1 = not tracked
        public float unitsPerDose = 1;      // units used by one dose
        public int refillAlertDays = 5;     // warn when stock covers fewer days than this

        public bool TracksStock => stock >= 0;

        public DateTime StartDate => TimeUtil.ParseDate(startDate);

        /// <summary>Last calendar day (inclusive) of the course, or null when ongoing.</summary>
        public DateTime? EndDate => durationDays > 0 ? StartDate.AddDays(durationDays - 1) : (DateTime?)null;

        public bool Active => pauses.Count == 0 || !pauses[pauses.Count - 1].EndsWith("|");

        public void Pause(DateTime now) { if (Active) pauses.Add(TimeUtil.Minute(now) + "|"); }

        public void Resume(DateTime now) { if (!Active) pauses[pauses.Count - 1] += TimeUtil.Minute(now); }

        /// <summary>Doses scheduled while paused are neither due nor missed.</summary>
        public bool IsPausedAt(DateTime t)
        {
            foreach (var p in pauses)
            {
                int bar = p.IndexOf('|');
                if (bar < 0) continue;
                var from = TimeUtil.ParseMinute(p.Substring(0, bar));
                string end = p.Substring(bar + 1);
                if (t >= from && (end.Length == 0 || t < TimeUtil.ParseMinute(end))) return true;
            }
            return false;
        }

        public string TimesLabel => string.Join(", ", times.ToArray());
    }

    /// <summary>What the patient did for one scheduled dose. Only doses with an action are stored.</summary>
    [Serializable]
    public class DoseRecord
    {
        public string doseKey = "";      // DoseKey.Make(medId, scheduled)
        public string medId = "";
        public string scheduled = "";    // "yyyy-MM-dd HH:mm" local
        public int status;               // DoseStatus
        public string actionAt = "";     // "yyyy-MM-dd HH:mm:ss" local
        public int verification;         // VerificationStatus
        public float livenessScore;      // 0..1 from observation session
        public float presenceScore;      // 0..1 from observation session
        public List<string> evidence = new List<string>(); // image file paths
        public string note = "";

        public DoseStatus Status { get => (DoseStatus)status; set => status = (int)value; }
        public VerificationStatus Verification { get => (VerificationStatus)verification; set => verification = (int)value; }
        public DateTime Scheduled => TimeUtil.ParseMinute(scheduled);
        public DateTime ActionAt => TimeUtil.ParseSecond(actionAt);
    }

    [Serializable]
    public class AppSettings
    {
        public int graceMinutes = 120;      // after this, an un-actioned dose counts as missed
        public int onTimeWindowMinutes = 60; // taken within +/- this = on time
        public int snoozeMinutes = 10;
        public int scheduleHorizonDays = 14; // how far ahead alarms are registered
        public string pharmacistPin = "0000";
        public bool lockEditingWithPin;
        public string patientName = "";
    }

    /// <summary>Everything persisted to disk.</summary>
    [Serializable]
    public class AppData
    {
        public int version = 1;
        public List<Medication> medications = new List<Medication>();
        public List<DoseRecord> records = new List<DoseRecord>();
        public AppSettings settings = new AppSettings();

        public Medication FindMed(string id) => medications.Find(m => m.id == id);
        public DoseRecord FindRecord(string doseKey) => records.Find(r => r.doseKey == doseKey);
    }

    /// <summary>A concrete occurrence of a medication at a time.</summary>
    public struct ScheduledDose
    {
        public Medication Med;
        public DateTime Time;
        public string Key => DoseKey.Make(Med.id, Time);
    }

    public static class DoseKey
    {
        public static string Make(string medId, DateTime t) => medId + "|" + t.ToString("yyyyMMddHHmm", CultureInfo.InvariantCulture);

        public static bool TryParse(string key, out string medId, out DateTime time)
        {
            medId = null;
            time = default;
            if (string.IsNullOrEmpty(key)) return false;
            int bar = key.LastIndexOf('|');
            if (bar <= 0) return false;
            medId = key.Substring(0, bar);
            return DateTime.TryParseExact(key.Substring(bar + 1), "yyyyMMddHHmm", CultureInfo.InvariantCulture, DateTimeStyles.None, out time);
        }
    }

    public static class TimeUtil
    {
        static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

        public static string Date(DateTime d) => d.ToString("yyyy-MM-dd", Inv);
        public static string Minute(DateTime d) => d.ToString("yyyy-MM-dd HH:mm", Inv);
        public static string Second(DateTime d) => d.ToString("yyyy-MM-dd HH:mm:ss", Inv);
        public static string Clock(DateTime d) => d.ToString("HH:mm", Inv);

        public static DateTime ParseDate(string s) =>
            DateTime.TryParseExact(s, "yyyy-MM-dd", Inv, DateTimeStyles.None, out var d) ? d : DateTime.Today;

        public static DateTime ParseMinute(string s) =>
            DateTime.TryParseExact(s, "yyyy-MM-dd HH:mm", Inv, DateTimeStyles.None, out var d) ? d : DateTime.MinValue;

        public static DateTime ParseSecond(string s) =>
            DateTime.TryParseExact(s, "yyyy-MM-dd HH:mm:ss", Inv, DateTimeStyles.None, out var d) ? d : DateTime.MinValue;

        /// <summary>Accepts "8", "8:00", "08:00", "0800", "8am", "8:30 pm". Returns normalised "HH:mm".</summary>
        public static bool TryNormalizeClock(string raw, out string clock)
        {
            clock = null;
            if (raw == null) return false;
            string s = raw.Trim().ToLowerInvariant().Replace(".", ":").Replace(" ", "");
            bool pm = s.EndsWith("pm"), am = s.EndsWith("am");
            if (pm || am) s = s.Substring(0, s.Length - 2);
            int h, m = 0;
            if (s.Contains(":"))
            {
                var parts = s.Split(':');
                if (parts.Length != 2 || !int.TryParse(parts[0], out h) || !int.TryParse(parts[1], out m)) return false;
            }
            else if (s.Length == 4 && int.TryParse(s, out int hhmm)) { h = hhmm / 100; m = hhmm % 100; }
            else if (!int.TryParse(s, out h)) return false;

            if (pm || am)
            {
                if (h < 1 || h > 12) return false;
                if (h == 12) h = 0;
                if (pm) h += 12;
            }
            if (h < 0 || h > 23 || m < 0 || m > 59) return false;
            clock = h.ToString("00") + ":" + m.ToString("00");
            return true;
        }
    }
}
