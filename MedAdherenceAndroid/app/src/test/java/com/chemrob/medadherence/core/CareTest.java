package com.chemrob.medadherence.core;

import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.*;

/** Dose forms, the drug table, courses, missed doses and side effects. */
public class CareTest {
    @Test public void drugClassesAndAntimicrobials() {
        assertEquals(DrugInfo.DrugClass.PENICILLIN, DrugInfo.classOf("Amoxicillin 500"));
        assertEquals(DrugInfo.DrugClass.PENICILLIN, DrugInfo.classOf("Augmentin 625 Duo"));
        assertEquals(DrugInfo.DrugClass.FLUOROQUINOLONE, DrugInfo.classOf("Moxifloxacin eye drops"));
        assertEquals(DrugInfo.DrugClass.ANTI_TB, DrugInfo.classOf("AKT-4"));
        assertTrue(DrugInfo.isAntimicrobial("Azithromycin"));
        assertFalse(DrugInfo.isAntimicrobial("Metformin"));
        assertFalse(DrugInfo.isAntimicrobial("Paracetamol"));
        assertNull(DrugInfo.classOf("Ferrous sulfate")); // "sulfate" is not a sulfa drug
    }

    @Test public void allergyCheck() {
        assertEquals(DrugInfo.Level.DANGER, DrugInfo.checkAllergy("Penicillin (rash)", "Amoxicillin").level);
        assertEquals(DrugInfo.Level.DANGER, DrugInfo.checkAllergy("amoxicillin", "Augmentin").level);
        assertEquals(DrugInfo.Level.CAUTION, DrugInfo.checkAllergy("penicillin", "Cefixime").level);
        assertEquals(DrugInfo.Level.DANGER, DrugInfo.checkAllergy("sulfa drugs", "Co-trimoxazole").level);
        assertEquals(DrugInfo.Level.DANGER, DrugInfo.checkAllergy("Aspirin", "Ibuprofen").level);
        assertEquals(DrugInfo.Level.DANGER, DrugInfo.checkAllergy("Paracetamol", "Paracetamol 650").level);
        assertEquals(DrugInfo.Level.NONE, DrugInfo.checkAllergy("penicillin", "Azithromycin").level);
        assertEquals(DrugInfo.Level.NONE, DrugInfo.checkAllergy("", "Amoxicillin").level);
        assertEquals(DrugInfo.Level.NONE, DrugInfo.checkAllergy("iron", "Ferrous sulfate").level);
    }

    @Test public void foodAdvice() {
        assertTrue(DrugInfo.advice("Ciprofloxacin").contains("antacids"));
        assertTrue(DrugInfo.advice("Doxycycline").contains("upright"));
        assertTrue(DrugInfo.advice("Metronidazole").contains("alcohol"));
        assertTrue(DrugInfo.advice("Rifampicin").contains("orange"));
        assertTrue(DrugInfo.advice("Pantoprazole").contains("before breakfast"));
        assertEquals("", DrugInfo.advice("Amoxicillin"));
    }

    @Test public void forms() {
        assertEquals(DoseForm.INHALER, DoseForm.of("inhaler"));
        assertEquals(DoseForm.TABLET, DoseForm.of("unknown"));
        assertTrue(DoseForm.EYE.hasSide());
        assertFalse(DoseForm.INHALER.observable);
        assertTrue(DoseForm.LIQUID.observable);
        assertFalse(DoseForm.SKIN.countsStock());
        for (DoseForm f : DoseForm.values()) assertTrue(f.howTo.length >= 2);
    }

    @Test public void courseProgressAndLeftovers() {
        Medication m = CoreTest.med("08:00, 20:00", 5, "2026-09-26", 1); // 26-30 Sept, 10 doses
        AppData d = CoreTest.with(m);
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
        ScheduleEngine.record(d, new ScheduledDose(m, LocalDateTime.of(2026, 9, 26, 8, 0)).key(), DoseStatus.TAKEN, LocalDateTime.of(2026, 9, 26, 8, 5));
        Course c = Course.of(d, m, now);
        assertEquals(3, c.day);
        assertEquals(5, c.days);
        assertEquals(10, c.total);
        assertEquals(1, c.taken);
        assertEquals(5, c.left); // 28th 20:00, then 29th and 30th
        assertFalse(c.finished);
        assertFalse(Course.needsLeftoverCheck(d, m, now));

        LocalDateTime after = LocalDateTime.of(2026, 10, 2, 9, 0);
        assertTrue(Course.of(d, m, after).finished);
        assertEquals(5, Course.of(d, m, after).day);
        assertTrue(Course.needsLeftoverCheck(d, m, after));
        m.leftover = 4;
        assertFalse(Course.needsLeftoverCheck(d, m, after));

        assertNull(Course.of(d, CoreTest.med("08:00", 0, "2026-09-01", 1), now)); // ongoing
        m.leftover = -1;
        m.form = "skin";
        assertFalse(Course.needsLeftoverCheck(d, m, after));
    }

    @Test public void missedDoseAdvice() {
        LocalDateTime dose = LocalDateTime.of(2026, 9, 28, 8, 0), next = LocalDateTime.of(2026, 9, 28, 20, 0);
        assertEquals(Course.Missed.TAKE_NOW, Course.missedAdvice(dose, next, dose.plusHours(5)));
        assertEquals(Course.Missed.SKIP, Course.missedAdvice(dose, next, dose.plusHours(7)));
        assertEquals(Course.Missed.TAKE_NOW, Course.missedAdvice(dose, null, dose.plusHours(20)));
        Medication m = CoreTest.med("08:00, 20:00", 0, "2026-09-01", 1);
        assertEquals(next, Course.nextDose(m, dose));
    }

    @Test public void newFieldsRoundTrip() throws Exception {
        AppData d = new AppData();
        Medication m = CoreTest.med("08:00", 5, "2026-09-26", 1);
        m.form = "eye"; m.side = "left"; m.leftover = 2;
        d.medications.add(m);
        d.profile.pharmacistPhone = "+91 98765 00000";
        d.settings.lastCheckIn = "2026-09-28";
        SideEffect e = new SideEffect();
        e.at = "2026-09-28 09:00"; e.symptom = SideEffect.Symptom.RASH; e.medicines = "Amoxicillin";
        d.sideEffects.add(e);
        AppData b = JsonCodec.fromJson(JsonCodec.toJson(d));
        assertEquals(DoseForm.EYE, b.medications.get(0).doseForm());
        assertEquals("left", b.medications.get(0).side);
        assertEquals(2, b.medications.get(0).leftover, 0);
        assertEquals("+91 98765 00000", b.profile.pharmacistPhone);
        assertEquals("2026-09-28", b.settings.lastCheckIn);
        assertEquals(SideEffect.Symptom.RASH, b.sideEffects.get(0).symptom);
        assertTrue(SideEffect.Symptom.BREATHING.serious);
        assertEquals(-1, JsonCodec.fromJson("{\"medications\":[{\"name\":\"x\"}]}").medications.get(0).leftover, 0);
    }
}
