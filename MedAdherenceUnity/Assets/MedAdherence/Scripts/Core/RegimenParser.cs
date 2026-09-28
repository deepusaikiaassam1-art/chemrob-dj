using System;
using System.Collections.Generic;

namespace MedAdherence.Core
{
    /// <summary>
    /// Fast regimen entry for pharmacists: frequency shorthands (OD, BD, TDS, ...) and bulk import,
    /// one medicine per line:
    ///   Name | Dose | Times or frequency | Duration | Start | Observed | Instructions | Stock | Units per dose
    /// e.g.
    ///   Metformin | 500 mg, 1 tab | BD | 30 | today | no | after food
    ///   Rifampicin, 600 mg, 07:00, 6m, 2026-10-01, yes, empty stomach
    ///   Methotrexate | 7.5 mg | WEEKLY 09:00 | 12w | today | no |
    ///   Amlodipine | 5 mg | OD | 0 | today | no | | 28 | 1
    /// Stock (units on hand) is optional; leave it empty not to track refills.
    /// Only the name is required. Separator is '|' if present on the line, otherwise ','.
    /// </summary>
    public static class RegimenParser
    {
        public class Frequency
        {
            public string code, label;
            public string[] times;
            public int everyNDays = 1;
        }

        public static readonly Frequency[] Presets =
        {
            new Frequency { code = "OD",  label = "Once daily",        times = new[] { "08:00" } },
            new Frequency { code = "BD",  label = "Twice daily",       times = new[] { "08:00", "20:00" } },
            new Frequency { code = "TDS", label = "Three times daily", times = new[] { "08:00", "14:00", "20:00" } },
            new Frequency { code = "QID", label = "Four times daily",  times = new[] { "06:00", "12:00", "18:00", "22:00" } },
            new Frequency { code = "HS",  label = "At bedtime",        times = new[] { "22:00" } },
            new Frequency { code = "Q8H", label = "Every 8 hours",     times = new[] { "06:00", "14:00", "22:00" } },
            new Frequency { code = "WEEKLY", label = "Once weekly",    times = new[] { "08:00" }, everyNDays = 7 },
        };

        static readonly Dictionary<string, string> Aliases = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            { "OD", "OD" }, { "QD", "OD" }, { "DAILY", "OD" }, { "ONCE", "OD" }, { "1X", "OD" },
            { "BD", "BD" }, { "BID", "BD" }, { "TWICE", "BD" }, { "2X", "BD" }, { "Q12H", "BD" },
            { "TDS", "TDS" }, { "TID", "TDS" }, { "3X", "TDS" },
            { "QID", "QID" }, { "QDS", "QID" }, { "4X", "QID" }, { "Q6H", "QID" },
            { "HS", "HS" }, { "NOCTE", "HS" }, { "BEDTIME", "HS" },
            { "Q8H", "Q8H" },
            { "WEEKLY", "WEEKLY" }, { "QW", "WEEKLY" }, { "ONCEWEEKLY", "WEEKLY" },
        };

        public static Frequency FindPreset(string codeOrAlias)
        {
            if (codeOrAlias == null) return null;
            if (!Aliases.TryGetValue(codeOrAlias.Trim().Replace(" ", ""), out var code)) return null;
            return Array.Find(Presets, p => p.code == code);
        }

        /// <summary>Parses "08:00 20:00", "8am;8pm", "BD" etc. into sorted HH:mm times.</summary>
        public static bool TryParseTimes(string raw, out List<string> times, out int everyNDays, out string error)
        {
            times = new List<string>();
            everyNDays = 1;
            error = null;
            if (string.IsNullOrWhiteSpace(raw)) { error = "No time given"; return false; }

            var preset = FindPreset(raw);
            if (preset != null)
            {
                times.AddRange(preset.times);
                everyNDays = preset.everyNDays;
                return true;
            }

            foreach (var tok in raw.Split(new[] { ' ', ';', '/', ',', '+' }, StringSplitOptions.RemoveEmptyEntries))
            {
                // Interval prefix: "WEEKLY 09:00" or "Q2D 08:00" (every 2 days).
                if (tok.Equals("WEEKLY", StringComparison.OrdinalIgnoreCase)) { everyNDays = 7; continue; }
                string up = tok.ToUpperInvariant();
                if (up.Length > 2 && up[0] == 'Q' && up[up.Length - 1] == 'D' && int.TryParse(up.Substring(1, up.Length - 2), out int n) && n > 0)
                { everyNDays = n; continue; }
                if (!TimeUtil.TryNormalizeClock(tok, out var c)) { error = "Cannot read time '" + tok + "'"; return false; }
                if (!times.Contains(c)) times.Add(c);
            }
            if (times.Count == 0) { error = "No time given"; return false; }
            times.Sort(StringComparer.Ordinal);
            return true;
        }

        /// <summary>"10", "10d", "2w", "3m", "ongoing". Returns days (0 = ongoing).</summary>
        public static bool TryParseDuration(string raw, out int days)
        {
            days = 0;
            if (string.IsNullOrWhiteSpace(raw)) return true;
            string s = raw.Trim().ToLowerInvariant().Replace(" ", "");
            if (s == "ongoing" || s == "continue" || s == "long-term" || s == "chronic" || s == "0") return true;
            int mult = 1;
            if (s.EndsWith("days")) s = s.Substring(0, s.Length - 4);
            else if (s.EndsWith("weeks")) { mult = 7; s = s.Substring(0, s.Length - 5); }
            else if (s.EndsWith("months")) { mult = 30; s = s.Substring(0, s.Length - 6); }
            else if (s.EndsWith("d")) s = s.Substring(0, s.Length - 1);
            else if (s.EndsWith("w")) { mult = 7; s = s.Substring(0, s.Length - 1); }
            else if (s.EndsWith("m")) { mult = 30; s = s.Substring(0, s.Length - 1); }
            if (!int.TryParse(s, out int n) || n < 0) return false;
            days = n * mult;
            return true;
        }

        public static bool ParseYesNo(string raw)
        {
            if (raw == null) return false;
            switch (raw.Trim().ToLowerInvariant())
            {
                case "y": case "yes": case "true": case "1": case "dot": case "observed": case "observe": return true;
                default: return false;
            }
        }

        public class ImportResult
        {
            public List<Medication> medications = new List<Medication>();
            public List<string> errors = new List<string>();
        }

        public static ImportResult Import(string text, DateTime today, string addedBy = "pharmacist")
        {
            var result = new ImportResult();
            if (string.IsNullOrEmpty(text)) return result;
            var lines = text.Replace("\r", "").Split('\n');
            for (int i = 0; i < lines.Length; i++)
            {
                string line = lines[i].Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                char sep = line.Contains("|") ? '|' : ',';
                var f = line.Split(sep);
                for (int k = 0; k < f.Length; k++) f[k] = f[k].Trim();
                if (i == 0 && f[0].Equals("name", StringComparison.OrdinalIgnoreCase)) continue; // header row

                Func<int, string> Field = k => k < f.Length ? f[k] : "";
                var med = new Medication { name = Field(0), dose = Field(1), instructions = Field(6), addedBy = addedBy };
                if (med.name.Length == 0) { result.errors.Add("Line " + (i + 1) + ": missing medicine name"); continue; }

                string timesRaw = Field(2).Length > 0 ? Field(2) : "OD";
                if (!TryParseTimes(timesRaw, out med.times, out med.everyNDays, out var err))
                { result.errors.Add("Line " + (i + 1) + ": " + err); continue; }

                if (!TryParseDuration(Field(3), out med.durationDays))
                { result.errors.Add("Line " + (i + 1) + ": cannot read duration '" + Field(3) + "'"); continue; }

                string start = Field(4);
                if (start.Length == 0 || start.Equals("today", StringComparison.OrdinalIgnoreCase)) med.startDate = TimeUtil.Date(today);
                else if (start.Equals("tomorrow", StringComparison.OrdinalIgnoreCase)) med.startDate = TimeUtil.Date(today.AddDays(1));
                else if (DateTime.TryParseExact(start, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture,
                             System.Globalization.DateTimeStyles.None, out var sd)) med.startDate = TimeUtil.Date(sd);
                else { result.errors.Add("Line " + (i + 1) + ": start date must be yyyy-MM-dd, 'today' or 'tomorrow'"); continue; }

                med.observed = ParseYesNo(Field(5));

                if (Field(7).Length > 0)
                {
                    if (!TryParseNumber(Field(7), out med.stock) || med.stock < 0)
                    { result.errors.Add("Line " + (i + 1) + ": stock must be a number of units, e.g. 30"); continue; }
                    if (Field(8).Length > 0 && (!TryParseNumber(Field(8), out med.unitsPerDose) || med.unitsPerDose <= 0))
                    { result.errors.Add("Line " + (i + 1) + ": units per dose must be a positive number, e.g. 1 or 0.5"); continue; }
                }
                result.medications.Add(med);
            }
            return result;
        }

        public static bool TryParseNumber(string raw, out float value) =>
            float.TryParse((raw ?? "").Trim().Replace(',', '.'), System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out value);

        /// <summary>Inverse of <see cref="Import"/>, so a regimen can be exported and re-loaded on another phone.</summary>
        public static string Export(IEnumerable<Medication> meds)
        {
            var sb = new System.Text.StringBuilder();
            sb.AppendLine("# Name | Dose | Times | Duration days (0 = ongoing) | Start | Observed | Instructions | Stock | Units per dose");
            foreach (var m in meds)
            {
                string times = (m.everyNDays > 1 ? "Q" + m.everyNDays + "D " : "") + string.Join(" ", m.times.ToArray());
                sb.AppendLine(string.Join(" | ", new[] { m.name, m.dose, times, m.durationDays.ToString(), m.startDate, m.observed ? "yes" : "no", m.instructions,
                    m.TracksStock ? m.stock.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture) : "",
                    m.TracksStock ? m.unitsPerDose.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture) : "" }).TrimEnd(' ', '|'));
            }
            return sb.ToString();
        }
    }
}
