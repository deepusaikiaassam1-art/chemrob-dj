using System;
using System.Linq;
using MedAdherence.Core;
using NUnit.Framework;

namespace MedAdherence.Tests
{
    public class InventoryTests
    {
        static AppData WithMed(Medication m)
        {
            var d = new AppData();
            d.medications.Add(m);
            return d;
        }

        [Test]
        public void TakingUsesStockAndUndoReturnsIt()
        {
            var m = new Medication { id = "m", times = { "08:00", "20:00" }, startDate = "2026-01-01", stock = 10, unitsPerDose = 2 };
            var data = WithMed(m);
            var d = ScheduleEngine.Doses(m, new DateTime(2026, 1, 1), new DateTime(2026, 1, 2)).First();

            ScheduleEngine.Record(data, d.Key, DoseStatus.Taken, d.Time);
            Assert.AreEqual(8f, m.stock, 1e-4f);
            ScheduleEngine.Record(data, d.Key, DoseStatus.Taken, d.Time.AddMinutes(1)); // same dose again: no double count
            Assert.AreEqual(8f, m.stock, 1e-4f);
            ScheduleEngine.Record(data, d.Key, DoseStatus.Skipped, d.Time.AddMinutes(2)); // corrected to skipped
            Assert.AreEqual(10f, m.stock, 1e-4f);
            ScheduleEngine.Record(data, d.Key, DoseStatus.Snoozed, d.Time.AddMinutes(3));
            Assert.AreEqual(10f, m.stock, 1e-4f);
        }

        [Test]
        public void UntrackedStockIsIgnored()
        {
            var m = new Medication { id = "m", times = { "08:00" }, startDate = "2026-01-01" };
            var data = WithMed(m);
            var d = ScheduleEngine.Doses(m, new DateTime(2026, 1, 1), new DateTime(2026, 1, 2)).First();
            ScheduleEngine.Record(data, d.Key, DoseStatus.Taken, d.Time);
            Assert.AreEqual(-1f, m.stock, 1e-4f);
            Assert.IsNull(Inventory.DaysLeft(m));
            Assert.IsFalse(Inventory.NeedsRefill(m, new DateTime(2026, 1, 1)));
            Assert.AreEqual("", Inventory.Label(m));
        }

        [Test]
        public void DaysLeftAndRefillWarning()
        {
            var now = new DateTime(2026, 1, 1, 9, 0, 0);
            var bd = new Medication { times = { "08:00", "20:00" }, startDate = "2026-01-01", stock = 12 };
            Assert.AreEqual(6f, Inventory.DaysLeft(bd).Value, 1e-4f);
            Assert.IsFalse(Inventory.NeedsRefill(bd, now)); // 6 days >= 5
            bd.stock = 7;
            Assert.IsTrue(Inventory.NeedsRefill(bd, now)); // 3.5 days
            StringAssert.Contains("7 left (~3 days)", Inventory.Label(bd));

            var weekly = new Medication { times = { "09:00" }, everyNDays = 7, startDate = "2026-01-01", stock = 2 };
            Assert.AreEqual(14f, Inventory.DaysLeft(weekly).Value, 1e-3f);

            var half = new Medication { times = { "08:00" }, startDate = "2026-01-01", stock = 1.5f, unitsPerDose = 0.5f };
            Assert.AreEqual(3, Inventory.DosesLeft(half));
        }

        [Test]
        public void CourseEndingBeforeStockRunsOutDoesNotWarn()
        {
            var now = new DateTime(2026, 1, 1, 9, 0, 0);
            // 5-day TDS course: 14 doses remain after 09:00 on day 1; 14 tablets is exactly enough.
            var m = new Medication { times = { "08:00", "14:00", "20:00" }, startDate = "2026-01-01", durationDays = 5, stock = 14 };
            Assert.AreEqual(14, Inventory.RemainingCourseDoses(m, now));
            Assert.IsFalse(Inventory.NeedsRefill(m, now));
            m.stock = 10;
            Assert.IsTrue(Inventory.NeedsRefill(m, now));
            Inventory.Refill(m, 30);
            Assert.AreEqual(40f, m.stock, 1e-4f);
        }

        [Test]
        public void PausedMedicineDoesNotWarn()
        {
            var m = new Medication { times = { "08:00" }, startDate = "2026-01-01", stock = 1 };
            m.Pause(new DateTime(2026, 1, 2));
            Assert.IsFalse(Inventory.NeedsRefill(m, new DateTime(2026, 1, 3)));
        }

        [Test]
        public void ImportAndExportStock()
        {
            var r = RegimenParser.Import("Amlodipine | 5 mg | OD | 0 | today | no | | 28 | 1\n" +
                                         "Warfarin | 5 mg | 18:00 | 0 | today | no | INR monthly | 0,5 | 0.5\n" +
                                         "Bad | 1 | OD | 0 | today | no | | lots |\n" +
                                         "Paracetamol | 500 mg | TDS | 3", new DateTime(2026, 1, 1));
            Assert.AreEqual(3, r.medications.Count);
            Assert.AreEqual(1, r.errors.Count);
            Assert.AreEqual(28f, r.medications[0].stock, 1e-4f);
            Assert.AreEqual(0.5f, r.medications[1].stock, 1e-4f);
            Assert.AreEqual(0.5f, r.medications[1].unitsPerDose, 1e-4f);
            Assert.IsFalse(r.medications[2].TracksStock);

            var again = RegimenParser.Import(RegimenParser.Export(r.medications), new DateTime(2026, 1, 1));
            Assert.AreEqual(0, again.errors.Count);
            Assert.AreEqual(28f, again.medications[0].stock, 1e-4f);
            Assert.AreEqual("INR monthly", again.medications[1].instructions);
            Assert.AreEqual(0.5f, again.medications[1].unitsPerDose, 1e-4f);
            Assert.IsFalse(again.medications[2].TracksStock);
        }
    }
}
