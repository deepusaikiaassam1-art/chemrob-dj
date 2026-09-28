package com.chemrob.medadherence.core;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CoreTest {
    static LocalDateTime t(int y, int mo, int d, int h, int mi) { return LocalDateTime.of(y, mo, d, h, mi); }
    static LocalDateTime d(int y, int mo, int d) { return LocalDateTime.of(y, mo, d, 0, 0); }

    static Medication med(String times, int days, String start, int every) {
        Medication m = new Medication();
        m.id = "m1";
        m.name = "Metformin";
        m.times = RegimenParser.parseTimes(times).times;
        m.durationDays = days;
        m.startDate = start;
        m.everyNDays = every;
        return m;
    }

    static AppData with(Medication m) {
        AppData a = new AppData();
        a.medications.add(m);
        return a;
    }

    // ---------------------------------------------------------------- schedule

    @Test public void expandsTimesWithinCourse() {
        List<ScheduledDose> doses = ScheduleEngine.doses(med("08:00 20:00", 3, "2026-01-10", 1), d(2026, 1, 1), d(2026, 2, 1));
        assertEquals(6, doses.size());
        assertEquals(t(2026, 1, 10, 8, 0), doses.get(0).time);
        assertEquals(t(2026, 1, 12, 20, 0), doses.get(5).time);
    }

    @Test public void ongoingAndWindowed() {
        assertEquals(2, ScheduleEngine.doses(med("08:00 20:00", 0, "2026-01-10", 1), t(2026, 3, 1, 12, 0), t(2026, 3, 2, 12, 0)).size());
    }

    @Test public void weeklyAlignsToStartDate() {
        List<ScheduledDose> doses = ScheduleEngine.doses(med("09:00", 28, "2026-01-05", 7), d(2026, 1, 7), d(2026, 3, 1));
        assertEquals(3, doses.size());
        assertEquals(t(2026, 1, 12, 9, 0), doses.get(0).time);
        assertEquals(t(2026, 1, 26, 9, 0), doses.get(2).time);
    }

    @Test public void pauseRemovesDosesOnlyInsideInterval() {
        Medication m = med("08:00 20:00", 0, "2026-01-10", 1);
        m.pause(d(2026, 1, 11));
        assertFalse(m.isActive());
        m.resume(d(2026, 1, 12));
        assertTrue(m.isActive());
        assertEquals(4, ScheduleEngine.doses(m, d(2026, 1, 10), d(2026, 1, 13)).size());
    }

    @Test public void statusDerivesMissedAfterGraceAndSnoozeDelaysRinging() {
        Medication m = med("08:00 20:00", 3, "2026-01-10", 1);
        AppData data = with(m);
        ScheduledDose dose = ScheduleEngine.doses(m, d(2026, 1, 10), d(2026, 1, 11)).get(0);
        assertEquals(DoseStatus.PENDING, ScheduleEngine.statusOf(data, dose, dose.time.plusMinutes(30)));
        assertTrue(ScheduleEngine.isDueNow(data, dose, dose.time.plusMinutes(30)));
        assertEquals(DoseStatus.MISSED, ScheduleEngine.statusOf(data, dose, dose.time.plusMinutes(121)));

        ScheduleEngine.record(data, dose.key(), DoseStatus.SNOOZED, dose.time.plusMinutes(1));
        assertFalse(ScheduleEngine.isDueNow(data, dose, dose.time.plusMinutes(5)));
        assertTrue(ScheduleEngine.isDueNow(data, dose, dose.time.plusMinutes(12)));
        assertEquals(dose.time.plusMinutes(11), ScheduleEngine.nextRingTime(data, dose));

        ScheduleEngine.record(data, dose.key(), DoseStatus.TAKEN, dose.time.plusMinutes(15));
        assertEquals(DoseStatus.TAKEN, ScheduleEngine.statusOf(data, dose, dose.time.plusDays(5)));
        assertFalse(ScheduleEngine.isDueNow(data, dose, dose.time.plusMinutes(20)));
        assertNotNull(ScheduleEngine.find(data, dose.key()));
        assertNull(ScheduleEngine.find(data, DoseKey.make("m1", t(2026, 1, 10, 9, 0))));
    }

    @Test public void doseKeyRoundTrips() {
        String k = DoseKey.make("abc", t(2026, 5, 6, 7, 8));
        assertEquals("abc", DoseKey.medId(k));
        assertEquals(t(2026, 5, 6, 7, 8), DoseKey.time(k));
        assertNull(DoseKey.time("garbage"));
    }

    // ---------------------------------------------------------------- adherence

    @Test public void computesTakingTimingAndDays() {
        Medication m = med("08:00 20:00", 2, "2026-01-01", 1);
        m.name = "A";
        AppData data = with(m);
        List<ScheduledDose> doses = ScheduleEngine.doses(m, d(2026, 1, 1), d(2026, 1, 3));
        ScheduleEngine.record(data, doses.get(0).key(), DoseStatus.TAKEN, doses.get(0).time.plusMinutes(10));
        ScheduleEngine.record(data, doses.get(1).key(), DoseStatus.TAKEN, doses.get(1).time.plusMinutes(90));
        ScheduleEngine.record(data, doses.get(2).key(), DoseStatus.SKIPPED, doses.get(2).time);

        AdherenceCalculator.Report r = AdherenceCalculator.compute(data, d(2026, 1, 1), d(2026, 1, 5));
        AdherenceStats o = r.overall;
        assertEquals(4, o.due);
        assertEquals(2, o.taken);
        assertEquals(1, o.onTime);
        assertEquals(1, o.late);
        assertEquals(1, o.skipped);
        assertEquals(1, o.missed);
        assertEquals(50.0, o.takingPercent(), 1e-9);
        assertEquals(25.0, o.timingPercent(), 1e-9);
        assertEquals(1, o.daysCovered);
        assertEquals(2, o.daysElapsed);
        assertEquals("Partially adherent", o.category());
        assertEquals(0, r.currentStreakDays);
    }

    @Test public void pendingDosesAreNotCounted() {
        AppData data = with(med("08:00", 0, "2026-01-01", 1));
        AdherenceCalculator.Report r = AdherenceCalculator.compute(data, d(2026, 1, 1), t(2026, 1, 1, 9, 0));
        assertEquals(0, r.overall.due);
        assertEquals(1, r.overall.pending);
    }

    @Test public void streakAndObservedVerification() {
        Medication m = med("07:00", 0, "2026-01-01", 1);
        m.name = "TB";
        m.observed = true;
        AppData data = with(m);
        for (ScheduledDose dz : ScheduleEngine.doses(m, d(2026, 1, 1), d(2026, 1, 4))) {
            DoseRecord rec = ScheduleEngine.record(data, dz.key(), DoseStatus.TAKEN, dz.time.plusMinutes(5));
            assertEquals(Verification.NEEDS_REVIEW, rec.verification);
            if (dz.time.getDayOfMonth() != 2) rec.verification = Verification.AUTO_VERIFIED;
        }
        AdherenceCalculator.Report r = AdherenceCalculator.compute(data, d(2026, 1, 1), t(2026, 1, 3, 12, 0));
        assertEquals(3, r.currentStreakDays);
        assertEquals(3, r.overall.observedDue);
        assertEquals(2, r.overall.observedVerified);
        assertTrue(AdherenceCalculator.toText(data, r).contains("Observed doses verified: 2/3"));
        assertTrue(AdherenceCalculator.doseLogCsv(data, d(2026, 1, 1), t(2026, 1, 3, 12, 0)).contains("TB,,2026-01-01 07:00,TAKEN"));
    }

    // ---------------------------------------------------------------- parser

    @Test public void normalisesClockFormats() {
        String[] cases = {"8", "08:00", "8:30", "08:30", "0830", "08:30", "8pm", "20:00", "12am", "00:00", "12:15 PM", "12:15", "21.45", "21:45"};
        for (int i = 0; i < cases.length; i += 2) assertEquals(cases[i], cases[i + 1], TimeUtil.normalizeClock(cases[i]));
        assertNull(TimeUtil.normalizeClock("25:00"));
        assertNull(TimeUtil.normalizeClock("13pm"));
    }

    @Test public void parsesFrequencyCodesAndTimes() {
        RegimenParser.Times t = RegimenParser.parseTimes("tid");
        assertEquals(Arrays.asList("08:00", "14:00", "20:00"), t.times);
        t = RegimenParser.parseTimes("WEEKLY 09:00");
        assertEquals(Arrays.asList("09:00"), t.times);
        assertEquals(7, t.everyNDays);
        assertEquals(Arrays.asList("08:00", "20:00"), RegimenParser.parseTimes("8pm; 8am").times);
        assertNotNull(RegimenParser.parseTimes("sometimes").error);
    }

    @Test public void parsesDurations() {
        assertEquals(Integer.valueOf(14), RegimenParser.parseDuration("2w"));
        assertEquals(Integer.valueOf(180), RegimenParser.parseDuration("6 months"));
        assertEquals(Integer.valueOf(0), RegimenParser.parseDuration("ongoing"));
        assertNull(RegimenParser.parseDuration("forever-ish"));
    }

    @Test public void importsBulkRegimenAndRoundTrips() {
        String text = "Name | Dose | Times\n"
                + "Metformin | 500 mg, 1 tab | BD | 30 | today | no | after food | 60 | 1\n"
                + "Rifampicin, 600 mg, 07:00, 6m, 2026-10-01, yes, empty stomach\n"
                + "# comment\n"
                + "Bad | 1 | 99:00\n"
                + "Methotrexate | 7.5 mg | WEEKLY 09:00 | 12w | tomorrow | no |\n"
                + "Warfarin | 5 mg | 18:00 | 0 | today | no | INR monthly | 0,5 | 0.5\n";
        RegimenParser.ImportResult r = RegimenParser.importText(text, LocalDate.of(2026, 9, 28), "pharmacist");
        assertEquals(4, r.medications.size());
        assertEquals(1, r.errors.size());
        assertEquals("500 mg, 1 tab", r.medications.get(0).dose);
        assertEquals(60.0, r.medications.get(0).stock, 1e-9);
        assertTrue(r.medications.get(1).observed);
        assertEquals(180, r.medications.get(1).durationDays);
        assertEquals(7, r.medications.get(2).everyNDays);
        assertEquals("2026-09-29", r.medications.get(2).startDate);
        assertEquals(0.5, r.medications.get(3).unitsPerDose, 1e-9);

        RegimenParser.ImportResult again = RegimenParser.importText(RegimenParser.export(r.medications), LocalDate.of(2026, 9, 28), "pharmacist");
        assertEquals(again.errors.toString(), 0, again.errors.size());
        for (int i = 0; i < 4; i++) {
            Medication a = r.medications.get(i), b = again.medications.get(i);
            assertEquals(a.times, b.times);
            assertEquals(a.everyNDays, b.everyNDays);
            assertEquals(a.durationDays, b.durationDays);
            assertEquals(a.observed, b.observed);
            assertEquals(a.stock, b.stock, 1e-9);
            assertEquals(a.instructions, b.instructions);
        }
    }

    // ---------------------------------------------------------------- stock

    @Test public void stockCountsDownAndBack() {
        Medication m = med("08:00 20:00", 0, "2026-01-01", 1);
        m.stock = 10;
        m.unitsPerDose = 2;
        AppData data = with(m);
        String k = ScheduleEngine.doses(m, d(2026, 1, 1), d(2026, 1, 2)).get(0).key();
        ScheduleEngine.record(data, k, DoseStatus.TAKEN, t(2026, 1, 1, 8, 0));
        assertEquals(8, m.stock, 1e-9);
        ScheduleEngine.record(data, k, DoseStatus.TAKEN, t(2026, 1, 1, 8, 1));
        assertEquals(8, m.stock, 1e-9);
        ScheduleEngine.record(data, k, DoseStatus.SKIPPED, t(2026, 1, 1, 8, 2));
        assertEquals(10, m.stock, 1e-9);
    }

    @Test public void refillWarnings() {
        LocalDateTime now = t(2026, 1, 1, 9, 0);
        Medication bd = med("08:00 20:00", 0, "2026-01-01", 1);
        bd.stock = 12;
        assertEquals(6.0, Inventory.daysLeft(bd), 1e-9);
        assertFalse(Inventory.needsRefill(bd, now));
        bd.stock = 7;
        assertTrue(Inventory.needsRefill(bd, now));
        assertEquals("7 left (~3 days)", Inventory.label(bd));

        Medication course = med("TDS", 5, "2026-01-01", 1);
        course.stock = 14;
        assertEquals(Integer.valueOf(14), Inventory.remainingCourseDoses(course, now));
        assertFalse(Inventory.needsRefill(course, now));
        course.stock = 10;
        assertTrue(Inventory.needsRefill(course, now));
        Inventory.refill(course, 30);
        assertEquals(40, course.stock, 1e-9);

        Medication untracked = med("08:00", 0, "2026-01-01", 1);
        assertNull(Inventory.daysLeft(untracked));
        assertEquals("", Inventory.label(untracked));
    }

    // ---------------------------------------------------------------- frame checks

    @Test public void frameChecks() {
        int w = 8, h = 8;
        byte[] skin = nv21(w, h, 160, 110, 150);  // Y, Cb, Cr inside the skin box
        byte[] wall = nv21(w, h, 160, 160, 110);
        byte[] dark = nv21(w, h, 5, 128, 128);
        assertTrue(FrameAnalysis.skinRatioNv21(skin, w, h, 1.0) > 0.9);
        assertTrue(FrameAnalysis.skinRatioNv21(wall, w, h, 1.0) < 0.01);
        assertTrue(FrameAnalysis.meanLuma(FrameAnalysis.sampleLuma(dark, w, h, 2)) < 0.05);
        byte[] a = FrameAnalysis.sampleLuma(skin, w, h, 2), b = FrameAnalysis.sampleLuma(nv21(w, h, 60, 128, 128), w, h, 2);
        assertEquals(0, FrameAnalysis.motion(a, a), 1e-9);
        assertTrue(FrameAnalysis.motion(a, b) > 0.3);
        assertTrue(FrameAnalysis.isSkinRgb(224, 172, 145));
        assertFalse(FrameAnalysis.isSkinRgb(90, 140, 200));

        FrameAnalysis.Criteria c = new FrameAnalysis.Criteria();
        assertTrue(c.stepPassed(0.5, 0.05, 0.3));
        assertFalse(c.stepPassed(0.5, 0.0, 0.3));
        assertEquals(Verification.AUTO_VERIFIED, c.verdict(5, 5, true));
        assertEquals(Verification.NEEDS_REVIEW, c.verdict(4, 5, true));
    }

    static byte[] nv21(int w, int h, int y, int cb, int cr) {
        byte[] b = new byte[w * h * 3 / 2];
        for (int i = 0; i < w * h; i++) b[i] = (byte) y;
        for (int i = w * h; i < b.length; i += 2) { b[i] = (byte) cr; b[i + 1] = (byte) cb; }
        return b;
    }

    // ---------------------------------------------------------------- json

    @Test public void jsonRoundTrip() throws Exception {
        Medication m = med("08:00 20:00", 7, "2026-01-01", 1);
        m.observed = true;
        m.stock = 3.5;
        m.pause(d(2026, 1, 3));
        m.photo = "/data/med_photos/m1.jpg";
        AppData data = with(m);
        data.settings.patientName = "Asha";
        DoseRecord rec = ScheduleEngine.record(data, DoseKey.make("m1", t(2026, 1, 1, 8, 0)), DoseStatus.TAKEN, t(2026, 1, 1, 8, 5));
        rec.evidence.add("/x/step1.jpg");
        rec.verification = Verification.AUTO_VERIFIED;

        AppData back = JsonCodec.fromJson(JsonCodec.toJson(data));
        Medication b = back.medications.get(0);
        assertEquals(m.times, b.times);
        assertTrue(b.observed);
        assertEquals(2.5, b.stock, 1e-9); // one dose taken from 3.5
        assertFalse(b.isActive());
        assertEquals("/data/med_photos/m1.jpg", b.photo);
        assertEquals("/data/med_photos/m1.jpg", b.copy().photo);
        assertEquals("Asha", back.settings.patientName);
        DoseRecord br = back.records.get(0);
        assertEquals(DoseStatus.TAKEN, br.status);
        assertEquals(Verification.AUTO_VERIFIED, br.verification);
        assertEquals(Arrays.asList("/x/step1.jpg"), br.evidence);
        assertEquals(0, JsonCodec.fromJson("{}").medications.size());
    }

    // ---------------------------------------------------------------- profile

    @Test public void profileValidationAgeAndRoundTrip() throws Exception {
        LocalDate today = LocalDate.of(2026, 9, 28);
        Profile p = new Profile();
        assertFalse(p.isComplete());
        assertNotNull(p.validate(today));
        p.name = "Asha Devi";
        assertNull(p.validate(today));
        p.dateOfBirth = "1962-10-01";
        assertEquals(Integer.valueOf(63), p.age(today));
        assertEquals("Asha", p.firstName());
        p.sex = "Female";
        assertEquals("Asha Devi, 63 y, Female", p.summary(today));
        p.dateOfBirth = "2030-01-01";
        assertNotNull(p.validate(today));
        p.dateOfBirth = "01/02/1960";
        assertNotNull(p.validate(today));
        p.dateOfBirth = "1962-10-01";
        p.phone = "+91 98765 43210";
        assertNull(p.validate(today));
        p.phone = "call me";
        assertNotNull(p.validate(today));
        p.phone = "";
        p.allergies = "Penicillin";
        p.conditions = "Type 2 diabetes";

        AppData d = new AppData();
        d.profile = p;
        d.settings.theme = "dark";
        AppData back = JsonCodec.fromJson(JsonCodec.toJson(d));
        assertEquals("Asha Devi", back.profile.name);
        assertEquals("Penicillin", back.profile.allergies);
        assertEquals("dark", back.settings.theme);

        AdherenceCalculator.Report r = AdherenceCalculator.compute(d, today.atStartOfDay(), today.atTime(12, 0));
        String text = AdherenceCalculator.toText(d, r);
        assertTrue(text.contains("Patient: Asha Devi, 63 y, Female"));
        assertTrue(text.contains("Allergies: Penicillin"));
    }

    @Test public void legacyPatientNameMigratesToProfile() throws Exception {
        AppData d = JsonCodec.fromJson("{\"settings\":{\"patientName\":\"Ravi\"}}");
        assertTrue(d.profile.isComplete());
        assertEquals("Ravi", d.profile.name);
    }
}
