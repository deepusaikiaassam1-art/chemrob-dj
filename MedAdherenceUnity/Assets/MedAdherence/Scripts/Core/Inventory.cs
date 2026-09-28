using System;
using System.Linq;

namespace MedAdherence.Core
{
    /// <summary>
    /// Stock on hand and refill warnings. Running out is one of the commonest reasons for missed
    /// doses, so the app counts down stock as doses are taken and warns before it runs out.
    /// </summary>
    public static class Inventory
    {
        /// <summary>Average doses per day, e.g. 2 for BD, 1/7 for weekly.</summary>
        public static float DosesPerDay(Medication med) =>
            med == null || med.times.Count == 0 ? 0f : (float)med.times.Count / Math.Max(1, med.everyNDays);

        /// <summary>Whole doses the current stock covers, or null if stock is not tracked.</summary>
        public static int? DosesLeft(Medication med)
        {
            if (med == null || !med.TracksStock) return null;
            float per = med.unitsPerDose > 0 ? med.unitsPerDose : 1f;
            return (int)Math.Floor(med.stock / per + 1e-4);
        }

        /// <summary>Days the current stock lasts at the prescribed rate, or null if not tracked.</summary>
        public static float? DaysLeft(Medication med)
        {
            var doses = DosesLeft(med);
            if (doses == null) return null;
            float perDay = DosesPerDay(med);
            return perDay <= 0 ? (float?)null : doses.Value / perDay;
        }

        /// <summary>Doses still scheduled from <paramref name="now"/> to the end of a fixed course; null if ongoing.</summary>
        public static int? RemainingCourseDoses(Medication med, DateTime now)
        {
            if (med?.EndDate == null) return null;
            return ScheduleEngine.Doses(med, now, med.EndDate.Value.AddDays(1)).Count();
        }

        /// <summary>
        /// True when stock is tracked, will run short of the prescription, and lasts fewer than
        /// <see cref="Medication.refillAlertDays"/> days. A course that ends before stock runs out never warns.
        /// </summary>
        public static bool NeedsRefill(Medication med, DateTime now)
        {
            var left = DosesLeft(med);
            if (left == null || !med.Active) return false;
            var remaining = RemainingCourseDoses(med, now);
            if (remaining != null && left.Value >= remaining.Value) return false;
            var days = DaysLeft(med);
            return days != null && days.Value < med.refillAlertDays;
        }

        /// <summary>Short status line, e.g. "12 left (6 days)"; empty if not tracked.</summary>
        public static string Label(Medication med)
        {
            var left = DosesLeft(med);
            if (left == null) return "";
            var days = DaysLeft(med);
            string units = med.stock == Math.Floor(med.stock) ? ((int)med.stock).ToString() : med.stock.ToString("0.#");
            return units + " left" + (days != null ? string.Format(" (~{0:0} day{1})", Math.Floor(days.Value), Math.Floor(days.Value) == 1 ? "" : "s") : "");
        }

        /// <summary>Keeps stock in step with the dose log: taking a dose uses stock, undoing it returns it.</summary>
        public static void OnStatusChanged(Medication med, DoseStatus previous, DoseStatus current)
        {
            if (med == null || !med.TracksStock || previous == current) return;
            float per = med.unitsPerDose > 0 ? med.unitsPerDose : 1f;
            if (current == DoseStatus.Taken) med.stock = Math.Max(0f, med.stock - per);
            else if (previous == DoseStatus.Taken) med.stock += per;
        }

        public static void Refill(Medication med, float units)
        {
            if (med == null || units <= 0) return;
            med.stock = Math.Max(0f, med.stock) + units;
        }
    }
}
