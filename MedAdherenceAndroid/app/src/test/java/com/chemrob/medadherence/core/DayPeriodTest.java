package com.chemrob.medadherence.core;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.Assert.*;

public class DayPeriodTest {
    @Test public void periodsOfTheDay() {
        assertEquals(DayPeriod.NIGHT, DayPeriod.of(LocalTime.of(2, 0)));
        assertEquals(DayPeriod.MORNING, DayPeriod.of(LocalTime.of(6, 0)));
        assertEquals(DayPeriod.MORNING, DayPeriod.of(LocalTime.of(8, 0)));
        assertEquals(DayPeriod.NOON, DayPeriod.of(LocalTime.of(14, 0)));
        assertEquals(DayPeriod.EVENING, DayPeriod.of(LocalTime.of(20, 0)));
        assertEquals(DayPeriod.NIGHT, DayPeriod.of(LocalTime.of(21, 30)));
        assertEquals(DayPeriod.NIGHT, DayPeriod.of(LocalTime.of(22, 0)));
        for (DayPeriod p : DayPeriod.values()) assertEquals(p, DayPeriod.of(LocalTime.parse(p.defaultTime)));
    }

    @Test public void frequencyCodes() {
        assertEquals("OD", DayPeriod.code(1, 1));
        assertEquals("BD", DayPeriod.code(2, 1));
        assertEquals("TDS", DayPeriod.code(3, 1));
        assertEquals("QID", DayPeriod.code(4, 1));
        assertEquals("WEEKLY", DayPeriod.code(1, 7));
        assertEquals("once a day", DayPeriod.codeLabel(1, 1));
        assertEquals("twice a day", DayPeriod.codeLabel(2, 1));
    }

    @Test public void pillboxGroupsTodaysDoses() {
        Medication a = CoreTest.med("08:00, 14:00, 20:00", 5, "2026-09-28", 1);
        Medication b = CoreTest.med("08:30, 22:00", 0, "2026-09-28", 1);
        b.id = "m2";
        AppData d = CoreTest.with(a);
        d.medications.add(b);
        ScheduleEngine.record(d, new ScheduledDose(a, LocalDateTime.of(2026, 9, 30, 8, 0)).key(), DoseStatus.TAKEN,
                LocalDateTime.of(2026, 9, 30, 8, 5));
        List<DayPeriod.Slot> box = DayPeriod.pillbox(d, LocalDateTime.of(2026, 9, 30, 9, 0));
        assertEquals(4, box.size());
        DayPeriod.Slot morning = box.get(0);
        assertEquals("08:00", morning.time);
        assertEquals(2, morning.doses);
        assertEquals(1, morning.taken);
        assertEquals(1, morning.due);         // 08:30 is still waiting
        assertFalse(morning.done());
        assertEquals("14:00", box.get(1).time);
        assertEquals(1, box.get(1).doses);
        assertEquals(1, box.get(2).doses);    // 20:00 is evening
        assertEquals("22:00", box.get(3).time);
        assertEquals(0, box.get(3).due);
    }
}
