using System;
using System.Collections.Generic;
using System.Text;

namespace MedAdherence.Core
{
    [Serializable]
    public class AdherenceStats
    {
        public string label = "";
        public int due;          // doses whose outcome is settled (taken/late/skipped/missed)
        public int taken;        // taken at any time (on time + late)
        public int onTime;       // taken within the on-time window
        public int late;
        public int skipped;
        public int missed;
        public int pending;      // due but still inside the grace window (not counted in due)
        public int observedDue;  // settled doses of camera-observed medicines
        public int observedVerified; // auto-verified or pharmacist-approved
        public int daysCovered;  // days on which every due dose was taken
        public int daysElapsed;  // days with at least one settled dose

        /// <summary>Dose-taking adherence: taken / due.</summary>
        public float TakingPercent => due == 0 ? 100f : 100f * taken / due;
        /// <summary>Timing adherence: taken on time / due.</summary>
        public float TimingPercent => due == 0 ? 100f : 100f * onTime / due;
        /// <summary>Proportion of days covered: fully-adherent days / days elapsed.</summary>
        public float DaysCoveredPercent => daysElapsed == 0 ? 100f : 100f * daysCovered / daysElapsed;
        public float VerifiedPercent => observedDue == 0 ? 100f : 100f * observedVerified / observedDue;

        /// <summary>Conventional 80 % threshold used in adherence research.</summary>
        public string Category =>
            due == 0 ? "No doses due yet" :
            TakingPercent >= 80f ? "Adherent" :
            TakingPercent >= 50f ? "Partially adherent" : "Non-adherent";
    }

    [Serializable]
    public class AdherenceReport
    {
        public DateTime from, to;
        public AdherenceStats overall = new AdherenceStats { label = "All medicines" };
        public List<AdherenceStats> perMedication = new List<AdherenceStats>();
        public int currentStreakDays; // consecutive fully-adherent days ending today/yesterday
    }

    public static class AdherenceCalculator
    {
        /// <summary>Adherence over [from, now]. Doses scheduled after now are ignored.</summary>
        public static AdherenceReport Compute(AppData data, DateTime from, DateTime now)
        {
            var report = new AdherenceReport { from = from, to = now };
            var perMed = new Dictionary<string, AdherenceStats>();
            var dayAll = new Dictionary<DateTime, bool>();              // day -> all settled doses taken
            var dayMed = new Dictionary<string, Dictionary<DateTime, bool>>();
            int window = data.settings.onTimeWindowMinutes;

            foreach (var med in data.medications)
            {
                perMed[med.id] = new AdherenceStats { label = med.name };
                dayMed[med.id] = new Dictionary<DateTime, bool>();
            }

            foreach (var dose in ScheduleEngine.Doses(data, from, now.AddTicks(1)))
            {
                var s = perMed[dose.Med.id];
                var status = ScheduleEngine.StatusOf(data, dose, now);
                if (status == DoseStatus.Pending || status == DoseStatus.Snoozed)
                {
                    s.pending++; report.overall.pending++;
                    continue;
                }

                bool ok = status == DoseStatus.Taken;
                Tally(s, data, dose, status, window);
                Tally(report.overall, data, dose, status, window);

                var d = dose.Time.Date;
                dayAll[d] = dayAll.TryGetValue(d, out var prev) ? prev && ok : ok;
                var dm = dayMed[dose.Med.id];
                dm[d] = dm.TryGetValue(d, out var prevM) ? prevM && ok : ok;
            }

            FillDays(report.overall, dayAll);
            foreach (var med in data.medications)
            {
                FillDays(perMed[med.id], dayMed[med.id]);
                report.perMedication.Add(perMed[med.id]);
            }
            report.currentStreakDays = Streak(dayAll, now.Date);
            return report;
        }

        static void Tally(AdherenceStats s, AppData data, ScheduledDose dose, DoseStatus status, int window)
        {
            s.due++;
            var rec = data.FindRecord(dose.Key);
            switch (status)
            {
                case DoseStatus.Taken:
                    s.taken++;
                    var at = rec != null ? rec.ActionAt : dose.Time;
                    if (Math.Abs((at - dose.Time).TotalMinutes) <= window) s.onTime++; else s.late++;
                    break;
                case DoseStatus.Skipped: s.skipped++; break;
                default: s.missed++; break;
            }
            if (dose.Med.observed)
            {
                s.observedDue++;
                if (rec != null && status == DoseStatus.Taken &&
                    (rec.Verification == VerificationStatus.AutoVerified || rec.Verification == VerificationStatus.PharmacistApproved))
                    s.observedVerified++;
            }
        }

        static void FillDays(AdherenceStats s, Dictionary<DateTime, bool> days)
        {
            s.daysElapsed = days.Count;
            s.daysCovered = 0;
            foreach (var kv in days) if (kv.Value) s.daysCovered++;
        }

        static int Streak(Dictionary<DateTime, bool> days, DateTime today)
        {
            // Today only counts if it is already settled; an unsettled today does not break the streak.
            var d = days.ContainsKey(today) ? today : today.AddDays(-1);
            int n = 0;
            while (days.TryGetValue(d, out var ok) && ok) { n++; d = d.AddDays(-1); }
            return n;
        }

        /// <summary>Plain-text summary suitable for sharing with a pharmacist or doctor.</summary>
        public static string ToText(AppData data, AdherenceReport r)
        {
            var sb = new StringBuilder();
            sb.AppendLine("Medication adherence report");
            if (!string.IsNullOrEmpty(data.settings.patientName)) sb.AppendLine("Patient: " + data.settings.patientName);
            sb.AppendLine("Period: " + TimeUtil.Date(r.from) + " to " + TimeUtil.Minute(r.to));
            sb.AppendLine();
            Append(sb, r.overall);
            sb.AppendLine("Current streak: " + r.currentStreakDays + " day(s)");
            sb.AppendLine();
            foreach (var s in r.perMedication) Append(sb, s);
            return sb.ToString();
        }

        static void Append(StringBuilder sb, AdherenceStats s)
        {
            sb.AppendLine(s.label + " - " + s.Category);
            sb.AppendLine(string.Format("  Doses due {0}: taken {1} (on time {2}, late {3}), skipped {4}, missed {5}",
                s.due, s.taken, s.onTime, s.late, s.skipped, s.missed));
            sb.AppendLine(string.Format("  Dose adherence {0:0.#}% | timing {1:0.#}% | days covered {2:0.#}%",
                s.TakingPercent, s.TimingPercent, s.DaysCoveredPercent));
            if (s.observedDue > 0)
                sb.AppendLine(string.Format("  Observed doses verified: {0}/{1} ({2:0.#}%)", s.observedVerified, s.observedDue, s.VerifiedPercent));
        }

        /// <summary>One row per settled dose, for spreadsheet analysis.</summary>
        public static string DoseLogCsv(AppData data, DateTime from, DateTime now)
        {
            var sb = new StringBuilder();
            sb.AppendLine("medicine,dose,scheduled,status,action_at,minutes_from_schedule,observed,verification,evidence_files");
            foreach (var dose in ScheduleEngine.Doses(data, from, now.AddTicks(1)))
            {
                var status = ScheduleEngine.StatusOf(data, dose, now);
                if (status == DoseStatus.Pending) continue;
                var rec = data.FindRecord(dose.Key);
                string actionAt = rec != null ? rec.actionAt : "";
                string delta = rec != null && status == DoseStatus.Taken ? ((int)(rec.ActionAt - dose.Time).TotalMinutes).ToString() : "";
                string ver = rec != null ? rec.Verification.ToString() : (dose.Med.observed ? "None" : "NotRequired");
                sb.AppendLine(string.Join(",", new[] {
                    Csv(dose.Med.name), Csv(dose.Med.dose), TimeUtil.Minute(dose.Time), status.ToString(), actionAt, delta,
                    dose.Med.observed ? "yes" : "no", ver, rec != null ? rec.evidence.Count.ToString() : "0" }));
            }
            return sb.ToString();
        }

        static string Csv(string s) => s != null && (s.Contains(",") || s.Contains("\"")) ? "\"" + s.Replace("\"", "\"\"") + "\"" : s;
    }
}
