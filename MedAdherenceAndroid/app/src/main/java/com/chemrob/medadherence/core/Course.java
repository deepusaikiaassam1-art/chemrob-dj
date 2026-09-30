package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Where a fixed-length course stands ("Day 3 of 5, 9 doses left"), and what to do when a dose
 * was missed. Ongoing medicines have no course.
 */
public final class Course {
    public int day, days;          // day of the course (1-based) and its length
    public int total, taken, left; // doses in the whole course, taken so far, still to come
    public boolean finished;

    /** Null for ongoing medicines, and before the course starts. */
    public static Course of(AppData d, Medication m, LocalDateTime now) {
        if (m.durationDays <= 0) return null;
        LocalDate start = m.start(), end = m.end();
        if (now.toLocalDate().isBefore(start)) return null;
        Course c = new Course();
        c.days = m.durationDays;
        c.day = (int) Math.min(c.days, ChronoUnit.DAYS.between(start, now.toLocalDate()) + 1);
        List<ScheduledDose> all = ScheduleEngine.doses(m, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        c.total = all.size();
        for (ScheduledDose x : all) {
            DoseStatus s = ScheduleEngine.statusOf(d, x, now);
            if (s == DoseStatus.TAKEN) c.taken++;
            else if (s == DoseStatus.PENDING || s == DoseStatus.SNOOZED) c.left++;
        }
        c.finished = c.left == 0 && now.toLocalDate().isAfter(end.minusDays(1));
        return c;
    }

    /** The course is over and nobody has yet said how much was left over. */
    public static boolean needsLeftoverCheck(AppData d, Medication m, LocalDateTime now) {
        if (m.leftover >= 0 || !DoseForm.of(m.form).countsStock()) return false;
        Course c = of(d, m, now);
        return c != null && c.finished && c.total > 0;
    }

    public enum Missed { TAKE_NOW, SKIP }

    /**
     * The usual rule for a late dose: take it as soon as remembered, unless it is nearer the next
     * dose than the missed one; then skip it. Never take two doses at once.
     * @param next the next scheduled dose of the same medicine, or null if there is none
     */
    public static Missed missedAdvice(LocalDateTime scheduled, LocalDateTime next, LocalDateTime now) {
        if (next == null) return Missed.TAKE_NOW;
        long half = ChronoUnit.MINUTES.between(scheduled, next) / 2;
        return ChronoUnit.MINUTES.between(scheduled, now) <= half ? Missed.TAKE_NOW : Missed.SKIP;
    }

    /** The next scheduled dose of the same medicine after {@code dose}, or null. */
    public static LocalDateTime nextDose(Medication m, LocalDateTime dose) {
        List<ScheduledDose> next = ScheduleEngine.doses(m, dose.plusMinutes(1), dose.plusDays(Math.max(8, m.everyNDays + 1)));
        return next.isEmpty() ? null : next.get(0).time;
    }
}
