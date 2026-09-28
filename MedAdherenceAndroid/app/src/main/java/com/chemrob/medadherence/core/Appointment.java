package com.chemrob.medadherence.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A doctor follow-up visit. */
public final class Appointment {
    /** Reminders go out this long before the visit. */
    public static final int[] REMINDER_MINUTES_BEFORE = {24 * 60, 2 * 60};

    public String id = UUID.randomUUID().toString().replace("-", "");
    public String when = "";       // yyyy-MM-dd HH:mm
    public String doctor = "";     // e.g. "Dr Sharma"
    public String place = "";      // e.g. "City Hospital, OPD 3"
    public String purpose = "";    // e.g. "Diabetes review, bring blood report"
    public boolean done;

    public LocalDateTime time() { return TimeUtil.parseMinute(when); }

    /** Upcoming = not marked done and not more than 2 hours in the past. */
    public boolean isUpcoming(LocalDateTime now) {
        LocalDateTime t = time();
        return !done && t != null && t.isAfter(now.minusHours(2));
    }

    /** Reminder moments that are still in the future. */
    public List<LocalDateTime> reminderTimes(LocalDateTime now) {
        List<LocalDateTime> out = new ArrayList<>();
        LocalDateTime t = time();
        if (done || t == null) return out;
        for (int m : REMINDER_MINUTES_BEFORE) {
            LocalDateTime r = t.minusMinutes(m);
            if (r.isAfter(now)) out.add(r);
        }
        return out;
    }

    /** "Dr Sharma, City Hospital" or whichever parts are filled in. */
    public String who() {
        String s = doctor.trim();
        if (!place.trim().isEmpty()) s = s.isEmpty() ? place.trim() : s + ", " + place.trim();
        return s.isEmpty() ? "Doctor follow-up" : s;
    }

    public String validate(LocalDateTime now) {
        LocalDateTime t = time();
        if (t == null) return "Enter the date and time like 2026-10-05 10:30.";
        if (doctor.trim().isEmpty() && place.trim().isEmpty()) return "Enter the doctor's name or the place.";
        if (t.isBefore(now.minusDays(1))) return "That date is in the past.";
        return null;
    }

    /** Next upcoming visit, or null. */
    public static Appointment next(List<Appointment> all, LocalDateTime now) {
        Appointment best = null;
        for (Appointment a : all)
            if (a.isUpcoming(now) && (best == null || a.time().isBefore(best.time()))) best = a;
        return best;
    }

    public Appointment copy() {
        Appointment a = new Appointment();
        a.id = id; a.when = when; a.doctor = doctor; a.place = place; a.purpose = purpose; a.done = done;
        return a;
    }
}
