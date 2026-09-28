using System;
using System.Collections.Generic;

namespace MedAdherence.Core
{
    /// <summary>Expands a regimen into concrete dose times and resolves each dose's status.</summary>
    public static class ScheduleEngine
    {
        /// <summary>All doses of <paramref name="med"/> with from &lt;= time &lt; to, in time order.</summary>
        public static IEnumerable<ScheduledDose> Doses(Medication med, DateTime from, DateTime to)
        {
            if (med == null || med.times.Count == 0) yield break;

            var clocks = new List<TimeSpan>();
            foreach (var t in med.times)
                if (TimeUtil.TryNormalizeClock(t, out var c)) clocks.Add(TimeSpan.Parse(c));
            clocks.Sort();

            DateTime start = med.StartDate.Date;
            DateTime? end = med.EndDate;
            int step = Math.Max(1, med.everyNDays);

            DateTime day = from.Date < start ? start : from.Date;
            // Align to the every-N-days cycle that begins on the start date.
            int offset = (int)((day - start).TotalDays % step);
            if (offset != 0) day = day.AddDays(step - offset);

            for (; day < to; day = day.AddDays(step))
            {
                if (end.HasValue && day > end.Value) yield break;
                foreach (var c in clocks)
                {
                    var t = day + c;
                    if (t >= from && t < to && !med.IsPausedAt(t)) yield return new ScheduledDose { Med = med, Time = t };
                }
            }
        }

        public static List<ScheduledDose> Doses(AppData data, DateTime from, DateTime to)
        {
            var list = new List<ScheduledDose>();
            foreach (var m in data.medications) list.AddRange(Doses(m, from, to));
            list.Sort((a, b) => a.Time.CompareTo(b.Time));
            return list;
        }

        /// <summary>Effective status of a dose at <paramref name="now"/>, deriving Missed from the grace window.</summary>
        public static DoseStatus StatusOf(AppData data, ScheduledDose dose, DateTime now)
        {
            var rec = data.FindRecord(dose.Key);
            if (rec != null && rec.Status != DoseStatus.Snoozed && rec.Status != DoseStatus.Pending) return rec.Status;
            if (now > dose.Time.AddMinutes(data.settings.graceMinutes)) return DoseStatus.Missed;
            return rec != null ? rec.Status : DoseStatus.Pending;
        }

        /// <summary>True while a dose should ring / be shown as "take now".</summary>
        public static bool IsDueNow(AppData data, ScheduledDose dose, DateTime now)
        {
            if (now < dose.Time) return false;
            var s = StatusOf(data, dose, now);
            if (s == DoseStatus.Pending) return true;
            if (s != DoseStatus.Snoozed) return false;
            var rec = data.FindRecord(dose.Key);
            return rec == null || now >= rec.ActionAt.AddMinutes(data.settings.snoozeMinutes);
        }

        /// <summary>Records (or overwrites) an action on a dose.</summary>
        public static DoseRecord Record(AppData data, string doseKey, DoseStatus status, DateTime at)
        {
            if (!DoseKey.TryParse(doseKey, out var medId, out var when)) return null;
            var rec = data.FindRecord(doseKey);
            if (rec == null)
            {
                rec = new DoseRecord { doseKey = doseKey, medId = medId, scheduled = TimeUtil.Minute(when) };
                var med = data.FindMed(medId);
                rec.Verification = med != null && med.observed ? VerificationStatus.NeedsReview : VerificationStatus.NotRequired;
                data.records.Add(rec);
            }
            rec.Status = status;
            rec.actionAt = TimeUtil.Second(at);
            return rec;
        }
    }
}
