using System;
using System.Linq;
using MedAdherence.Core;
using NUnit.Framework;

namespace MedAdherence.Tests
{
    public class ScheduleTests
    {
        static Medication Med(string times = "08:00 20:00", int days = 3, string start = "2026-01-10", int every = 1)
        {
            RegimenParser.TryParseTimes(times, out var ts, out _, out _);
            return new Medication { id = "m1", name = "Metformin", times = ts, durationDays = days, startDate = start, everyNDays = every };
        }

        [Test]
        public void ExpandsTimesWithinCourse()
        {
            var doses = ScheduleEngine.Doses(Med(), new DateTime(2026, 1, 1), new DateTime(2026, 2, 1)).ToList();
            Assert.AreEqual(6, doses.Count);
            Assert.AreEqual(new DateTime(2026, 1, 10, 8, 0, 0), doses[0].Time);
            Assert.AreEqual(new DateTime(2026, 1, 12, 20, 0, 0), doses[5].Time);
        }

        [Test]
        public void OngoingAndWindowed()
        {
            var doses = ScheduleEngine.Doses(Med(days: 0), new DateTime(2026, 3, 1, 12, 0, 0), new DateTime(2026, 3, 2, 12, 0, 0)).ToList();
            Assert.AreEqual(2, doses.Count); // 20:00 on the 1st and 08:00 on the 2nd
        }

        [Test]
        public void WeeklyAlignsToStartDate()
        {
            var doses = ScheduleEngine.Doses(Med("09:00", 28, "2026-01-05", 7), new DateTime(2026, 1, 7), new DateTime(2026, 3, 1)).ToList();
            Assert.AreEqual(3, doses.Count);
            Assert.AreEqual(new DateTime(2026, 1, 12, 9, 0, 0), doses[0].Time);
            Assert.AreEqual(new DateTime(2026, 1, 26, 9, 0, 0), doses[2].Time);
        }

        [Test]
        public void PauseRemovesDosesOnlyInsideInterval()
        {
            var m = Med(days: 0);
            m.Pause(new DateTime(2026, 1, 11, 0, 0, 0));
            Assert.IsFalse(m.Active);
            m.Resume(new DateTime(2026, 1, 12, 0, 0, 0));
            Assert.IsTrue(m.Active);
            var doses = ScheduleEngine.Doses(m, new DateTime(2026, 1, 10), new DateTime(2026, 1, 13)).ToList();
            Assert.AreEqual(4, doses.Count); // the 11th is skipped
        }

        [Test]
        public void StatusDerivesMissedAfterGrace()
        {
            var data = new AppData();
            var m = Med();
            data.medications.Add(m);
            var dose = ScheduleEngine.Doses(m, new DateTime(2026, 1, 10), new DateTime(2026, 1, 11)).First();
            Assert.AreEqual(DoseStatus.Pending, ScheduleEngine.StatusOf(data, dose, dose.Time.AddMinutes(30)));
            Assert.IsTrue(ScheduleEngine.IsDueNow(data, dose, dose.Time.AddMinutes(30)));
            Assert.AreEqual(DoseStatus.Missed, ScheduleEngine.StatusOf(data, dose, dose.Time.AddMinutes(121)));

            ScheduleEngine.Record(data, dose.Key, DoseStatus.Snoozed, dose.Time.AddMinutes(1));
            Assert.IsFalse(ScheduleEngine.IsDueNow(data, dose, dose.Time.AddMinutes(5)));
            Assert.IsTrue(ScheduleEngine.IsDueNow(data, dose, dose.Time.AddMinutes(12)));

            ScheduleEngine.Record(data, dose.Key, DoseStatus.Taken, dose.Time.AddMinutes(15));
            Assert.AreEqual(DoseStatus.Taken, ScheduleEngine.StatusOf(data, dose, dose.Time.AddDays(5)));
            Assert.IsFalse(ScheduleEngine.IsDueNow(data, dose, dose.Time.AddMinutes(20)));
        }

        [Test]
        public void DoseKeyRoundTrips()
        {
            var t = new DateTime(2026, 5, 6, 7, 8, 0);
            Assert.IsTrue(DoseKey.TryParse(DoseKey.Make("abc", t), out var id, out var back));
            Assert.AreEqual("abc", id);
            Assert.AreEqual(t, back);
        }
    }

    public class AdherenceTests
    {
        [Test]
        public void ComputesTakingTimingAndDays()
        {
            var data = new AppData();
            var m = new Medication { id = "m", name = "A", times = { "08:00", "20:00" }, startDate = "2026-01-01", durationDays = 2 };
            data.medications.Add(m);
            var doses = ScheduleEngine.Doses(m, new DateTime(2026, 1, 1), new DateTime(2026, 1, 3)).ToList();
            ScheduleEngine.Record(data, doses[0].Key, DoseStatus.Taken, doses[0].Time.AddMinutes(10));  // on time
            ScheduleEngine.Record(data, doses[1].Key, DoseStatus.Taken, doses[1].Time.AddMinutes(90));  // late
            ScheduleEngine.Record(data, doses[2].Key, DoseStatus.Skipped, doses[2].Time);               // skipped
            // doses[3] never recorded -> missed

            var r = AdherenceCalculator.Compute(data, new DateTime(2026, 1, 1), new DateTime(2026, 1, 5));
            var o = r.overall;
            Assert.AreEqual(4, o.due);
            Assert.AreEqual(2, o.taken);
            Assert.AreEqual(1, o.onTime);
            Assert.AreEqual(1, o.late);
            Assert.AreEqual(1, o.skipped);
            Assert.AreEqual(1, o.missed);
            Assert.AreEqual(50f, o.TakingPercent, 0.01f);
            Assert.AreEqual(25f, o.TimingPercent, 0.01f);
            Assert.AreEqual(1, o.daysCovered);
            Assert.AreEqual(2, o.daysElapsed);
            Assert.AreEqual("Partially adherent", o.Category);
            Assert.AreEqual(0, r.currentStreakDays);
        }

        [Test]
        public void PendingDosesAreNotCounted()
        {
            var data = new AppData();
            var m = new Medication { id = "m", name = "A", times = { "08:00" }, startDate = "2026-01-01" };
            data.medications.Add(m);
            var r = AdherenceCalculator.Compute(data, new DateTime(2026, 1, 1), new DateTime(2026, 1, 1, 9, 0, 0));
            Assert.AreEqual(0, r.overall.due);
            Assert.AreEqual(1, r.overall.pending);
        }

        [Test]
        public void StreakAndObservedVerification()
        {
            var data = new AppData();
            var m = new Medication { id = "m", name = "TB", times = { "07:00" }, startDate = "2026-01-01", observed = true };
            data.medications.Add(m);
            foreach (var d in ScheduleEngine.Doses(m, new DateTime(2026, 1, 1), new DateTime(2026, 1, 4)))
            {
                var rec = ScheduleEngine.Record(data, d.Key, DoseStatus.Taken, d.Time.AddMinutes(5));
                Assert.AreEqual(VerificationStatus.NeedsReview, rec.Verification);
                if (d.Time.Day != 2) rec.Verification = VerificationStatus.AutoVerified;
            }
            var r = AdherenceCalculator.Compute(data, new DateTime(2026, 1, 1), new DateTime(2026, 1, 3, 12, 0, 0));
            Assert.AreEqual(3, r.currentStreakDays);
            Assert.AreEqual(3, r.overall.observedDue);
            Assert.AreEqual(2, r.overall.observedVerified);
            StringAssert.Contains("Observed doses verified: 2/3", AdherenceCalculator.ToText(data, r));
            StringAssert.Contains("TB,,2026-01-01 07:00,Taken", AdherenceCalculator.DoseLogCsv(data, new DateTime(2026, 1, 1), new DateTime(2026, 1, 3, 12, 0, 0)));
        }
    }

    public class ParserTests
    {
        [Test]
        public void NormalisesClockFormats()
        {
            var cases = new[] { "8", "08:00", "8:30", "08:30", "0830", "08:30", "8pm", "20:00", "12am", "00:00", "12:15 PM", "12:15", "21.45", "21:45" };
            for (int i = 0; i < cases.Length; i += 2)
            {
                string raw = cases[i], want = cases[i + 1];
                Assert.IsTrue(TimeUtil.TryNormalizeClock(raw, out var got), raw);
                Assert.AreEqual(want, got, raw);
            }
            Assert.IsFalse(TimeUtil.TryNormalizeClock("25:00", out _));
            Assert.IsFalse(TimeUtil.TryNormalizeClock("13pm", out _));
        }

        [Test]
        public void ParsesFrequencyCodesAndTimes()
        {
            Assert.IsTrue(RegimenParser.TryParseTimes("tid", out var t, out var every, out _));
            CollectionAssert.AreEqual(new[] { "08:00", "14:00", "20:00" }, t);
            Assert.AreEqual(1, every);
            Assert.IsTrue(RegimenParser.TryParseTimes("WEEKLY 09:00", out t, out every, out _));
            CollectionAssert.AreEqual(new[] { "09:00" }, t);
            Assert.AreEqual(7, every);
            Assert.IsTrue(RegimenParser.TryParseTimes("8pm; 8am", out t, out _, out _));
            CollectionAssert.AreEqual(new[] { "08:00", "20:00" }, t);
            Assert.IsFalse(RegimenParser.TryParseTimes("sometimes", out _, out _, out _));
        }

        [Test]
        public void ParsesDurations()
        {
            Assert.IsTrue(RegimenParser.TryParseDuration("2w", out var d)); Assert.AreEqual(14, d);
            Assert.IsTrue(RegimenParser.TryParseDuration("6 months", out d)); Assert.AreEqual(180, d);
            Assert.IsTrue(RegimenParser.TryParseDuration("ongoing", out d)); Assert.AreEqual(0, d);
            Assert.IsFalse(RegimenParser.TryParseDuration("forever-ish", out _));
        }

        [Test]
        public void ImportsBulkRegimenAndReportsErrors()
        {
            string text = "Name | Dose | Times\n" +
                          "Metformin | 500 mg, 1 tab | BD | 30 | today | no | after food\n" +
                          "Rifampicin, 600 mg, 07:00, 6m, 2026-10-01, yes, empty stomach\n" +
                          "# comment\n" +
                          "Bad | 1 | 99:00\n" +
                          "Methotrexate | 7.5 mg | WEEKLY 09:00 | 12w | tomorrow | no |\n";
            var r = RegimenParser.Import(text, new DateTime(2026, 9, 28));
            Assert.AreEqual(3, r.medications.Count);
            Assert.AreEqual(1, r.errors.Count);
            var met = r.medications[0];
            Assert.AreEqual("500 mg, 1 tab", met.dose);
            Assert.AreEqual(30, met.durationDays);
            Assert.AreEqual("2026-09-28", met.startDate);
            var rif = r.medications[1];
            Assert.IsTrue(rif.observed);
            Assert.AreEqual(180, rif.durationDays);
            Assert.AreEqual("2026-10-01", rif.startDate);
            var mtx = r.medications[2];
            Assert.AreEqual(7, mtx.everyNDays);
            Assert.AreEqual("2026-09-29", mtx.startDate);

            // Export -> import round trip keeps the schedule.
            var again = RegimenParser.Import(RegimenParser.Export(r.medications), new DateTime(2026, 9, 28));
            Assert.AreEqual(0, again.errors.Count);
            for (int i = 0; i < 3; i++)
            {
                CollectionAssert.AreEqual(r.medications[i].times, again.medications[i].times);
                Assert.AreEqual(r.medications[i].everyNDays, again.medications[i].everyNDays);
                Assert.AreEqual(r.medications[i].durationDays, again.medications[i].durationDays);
                Assert.AreEqual(r.medications[i].observed, again.medications[i].observed);
            }
        }
    }

    public class FrameAnalysisTests
    {
        static byte[] Solid(int w, int h, byte r, byte g, byte b)
        {
            var px = new byte[w * h * 4];
            for (int i = 0; i < w * h; i++) { px[i * 4] = r; px[i * 4 + 1] = g; px[i * 4 + 2] = b; px[i * 4 + 3] = 255; }
            return px;
        }

        [Test]
        public void BrightnessMotionAndSkin()
        {
            var dark = Solid(8, 8, 5, 5, 5);
            var skin = Solid(8, 8, 224, 172, 145);
            var wall = Solid(8, 8, 90, 140, 200);
            Assert.Less(FrameAnalysis.MeanLuma(dark), 0.05f);
            Assert.Greater(FrameAnalysis.SkinRatio(skin, 8, 8), 0.9f);
            Assert.Less(FrameAnalysis.SkinRatio(wall, 8, 8), 0.01f);
            Assert.AreEqual(0f, FrameAnalysis.Motion(skin, skin));
            Assert.Greater(FrameAnalysis.Motion(skin, wall), 0.1f);

            var c = new ObservationCriteria();
            Assert.IsTrue(c.StepPassed(0.5f, 0.05f, 0.3f));
            Assert.IsFalse(c.StepPassed(0.5f, 0.0f, 0.3f)); // a still photo
            Assert.AreEqual(VerificationStatus.AutoVerified, c.Verdict(5, 5, true));
            Assert.AreEqual(VerificationStatus.NeedsReview, c.Verdict(4, 5, true));
        }

        [Test]
        public void RotatesClockwise()
        {
            // 2x1 image: A B (bottom-left origin). 90 cw -> 1x2 with A on top.
            var src = new byte[] { 1, 0, 0, 255, 2, 0, 0, 255 };
            var r90 = FrameAnalysis.RotateClockwise(src, 2, 1, 90, out int w, out int h);
            Assert.AreEqual(1, w); Assert.AreEqual(2, h);
            Assert.AreEqual(2, r90[0]); // bottom = B
            Assert.AreEqual(1, r90[4]); // top = A
            var r180 = FrameAnalysis.RotateClockwise(src, 2, 1, 180, out _, out _);
            Assert.AreEqual(2, r180[0]);
            var r270 = FrameAnalysis.RotateClockwise(src, 2, 1, 270, out _, out _);
            Assert.AreEqual(1, r270[0]); // bottom = A
        }

        [Test]
        public void FlipsVertically()
        {
            // 1x2 image: bottom = 1, top = 2
            var src = new byte[] { 1, 0, 0, 255, 2, 0, 0, 255 };
            var f = FrameAnalysis.FlipVertical(src, 1, 2);
            Assert.AreEqual(2, f[0]);
            Assert.AreEqual(1, f[4]);
        }
    }
}
