package com.chemrob.medadherence.core;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Morning, Noon, Evening and Night: how older patients think about their doses, like the
 * compartments of a pillbox. Used for the Today pillbox and the time picker when adding a medicine.
 */
public enum DayPeriod {
    MORNING("Morning", "08:00"),
    NOON("Noon", "13:00"),
    EVENING("Evening", "19:00"),
    NIGHT("Night", "21:30");

    public final String label;
    /** The time offered when the patient picks this period. */
    public final String defaultTime;

    DayPeriod(String label, String defaultTime) { this.label = label; this.defaultTime = defaultTime; }

    /** Which period a clock time falls in (the small hours count as night). */
    public static DayPeriod of(LocalTime t) {
        if (t.isBefore(LocalTime.of(4, 0))) return NIGHT;
        if (t.isBefore(LocalTime.of(11, 0))) return MORNING;
        if (t.isBefore(LocalTime.of(16, 0))) return NOON;
        if (t.isBefore(LocalTime.of(20, 30))) return EVENING;
        return NIGHT;
    }

    /** Short frequency code for a list of daily times ("OD", "BD", "TDS", "QID"), or "WEEKLY". */
    public static String code(int timesPerDay, int everyNDays) {
        if (everyNDays == 7 && timesPerDay == 1) return "WEEKLY";
        String c = timesPerDay == 1 ? "OD" : timesPerDay == 2 ? "BD" : timesPerDay == 3 ? "TDS" : timesPerDay == 4 ? "QID"
                : timesPerDay + "x";
        return everyNDays > 1 ? c + " / " + everyNDays + "d" : c;
    }

    /** Everyday words for the code, in English (translated by the UI). */
    public static String codeLabel(int timesPerDay, int everyNDays) {
        if (timesPerDay == 0) return "no reminders";
        if (everyNDays == 7 && timesPerDay == 1) return "once a week";
        String s = timesPerDay == 1 ? "once a day" : timesPerDay == 2 ? "twice a day" : timesPerDay == 3 ? "three times a day"
                : timesPerDay == 4 ? "four times a day" : "several times a day";
        return everyNDays > 1 ? s + ", every few days" : s;
    }

    /** One compartment of today's pillbox. */
    public static final class Slot {
        public final DayPeriod period;
        public final String time;      // earliest dose time in this period, or the default
        public final int doses, taken, missed, due;
        public Slot(DayPeriod period, String time, int doses, int taken, int missed, int due) {
            this.period = period; this.time = time; this.doses = doses; this.taken = taken; this.missed = missed; this.due = due;
        }
        public boolean empty() { return doses == 0; }
        public boolean done() { return doses > 0 && taken == doses; }
        /** Everything is settled but not all taken. */
        public boolean missedSome() { return missed > 0 && taken + missed == doses; }
    }

    /** Today's doses grouped into the four periods. */
    public static List<Slot> pillbox(AppData d, LocalDateTime now) {
        List<ScheduledDose> doses = ScheduleEngine.doses(d, now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay());
        List<Slot> out = new ArrayList<>();
        for (DayPeriod p : values()) {
            int n = 0, taken = 0, missed = 0, due = 0;
            LocalTime first = null;
            for (ScheduledDose x : doses) {
                if (of(x.time.toLocalTime()) != p) continue;
                n++;
                if (first == null || x.time.toLocalTime().isBefore(first)) first = x.time.toLocalTime();
                DoseStatus s = ScheduleEngine.statusOf(d, x, now);
                if (s == DoseStatus.TAKEN) taken++;
                else if (s == DoseStatus.MISSED || s == DoseStatus.SKIPPED) missed++;
                if (ScheduleEngine.isDueNow(d, x, now)) due++;
            }
            out.add(new Slot(p, first == null ? p.defaultTime : String.format(java.util.Locale.ROOT, "%02d:%02d", first.getHour(), first.getMinute()), n, taken, missed, due));
        }
        return out;
    }
}
