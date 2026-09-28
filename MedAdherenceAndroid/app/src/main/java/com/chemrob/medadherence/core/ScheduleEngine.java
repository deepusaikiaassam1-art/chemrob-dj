package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Expands a regimen into concrete dose times and resolves each dose's status. */
public final class ScheduleEngine {
    private ScheduleEngine() {}

    /** All doses of {@code med} with from <= time < to, in time order. */
    public static List<ScheduledDose> doses(Medication med, LocalDateTime from, LocalDateTime to) {
        List<ScheduledDose> out = new ArrayList<>();
        if (med == null || med.times.isEmpty()) return out;

        List<LocalTime> clocks = new ArrayList<>();
        for (String t : med.times) {
            LocalTime c = TimeUtil.parseClock(t);
            if (c != null && !clocks.contains(c)) clocks.add(c);
        }
        Collections.sort(clocks);

        LocalDate start = med.start();
        LocalDate end = med.end();
        int step = Math.max(1, med.everyNDays);

        LocalDate day = from.toLocalDate().isBefore(start) ? start : from.toLocalDate();
        long offset = ChronoUnit.DAYS.between(start, day) % step;
        if (offset != 0) day = day.plusDays(step - offset); // align to the every-N-days cycle

        for (; day.atStartOfDay().isBefore(to); day = day.plusDays(step)) {
            if (end != null && day.isAfter(end)) break;
            for (LocalTime c : clocks) {
                LocalDateTime t = day.atTime(c);
                if (!t.isBefore(from) && t.isBefore(to) && !med.isPausedAt(t)) out.add(new ScheduledDose(med, t));
            }
        }
        return out;
    }

    public static List<ScheduledDose> doses(AppData data, LocalDateTime from, LocalDateTime to) {
        List<ScheduledDose> list = new ArrayList<>();
        for (Medication m : data.medications) list.addAll(doses(m, from, to));
        list.sort((a, b) -> a.time.compareTo(b.time));
        return list;
    }

    /** Effective status at {@code now}, deriving MISSED from the grace window. */
    public static DoseStatus statusOf(AppData data, ScheduledDose dose, LocalDateTime now) {
        DoseRecord rec = data.findRecord(dose.key());
        if (rec != null && rec.status != DoseStatus.SNOOZED && rec.status != DoseStatus.PENDING) return rec.status;
        if (now.isAfter(dose.time.plusMinutes(data.settings.graceMinutes))) return DoseStatus.MISSED;
        return rec != null ? rec.status : DoseStatus.PENDING;
    }

    /** True while a dose should ring / be shown as "take now". */
    public static boolean isDueNow(AppData data, ScheduledDose dose, LocalDateTime now) {
        if (now.isBefore(dose.time)) return false;
        DoseStatus s = statusOf(data, dose, now);
        if (s == DoseStatus.PENDING) return true;
        if (s != DoseStatus.SNOOZED) return false;
        return !now.isBefore(nextRingTime(data, dose));
    }

    /** When a pending/snoozed dose should (next) ring. */
    public static LocalDateTime nextRingTime(AppData data, ScheduledDose dose) {
        DoseRecord rec = data.findRecord(dose.key());
        if (rec != null && rec.status == DoseStatus.SNOOZED && rec.actionTime() != null)
            return rec.actionTime().plusMinutes(data.settings.snoozeMinutes);
        return dose.time;
    }

    /** Finds the scheduled dose a key refers to, or null if it no longer exists in the regimen. */
    public static ScheduledDose find(AppData data, String doseKey) {
        Medication med = data.findMed(DoseKey.medId(doseKey));
        LocalDateTime t = DoseKey.time(doseKey);
        if (med == null || t == null) return null;
        for (ScheduledDose d : doses(med, t, t.plusMinutes(1))) if (d.time.equals(t)) return d;
        return null;
    }

    /** Records (or overwrites) an action on a dose and keeps stock in step. */
    public static DoseRecord record(AppData data, String doseKey, DoseStatus status, LocalDateTime at) {
        String medId = DoseKey.medId(doseKey);
        LocalDateTime when = DoseKey.time(doseKey);
        if (medId == null || when == null) return null;
        Medication med = data.findMed(medId);
        DoseRecord rec = data.findRecord(doseKey);
        if (rec == null) {
            rec = new DoseRecord();
            rec.doseKey = doseKey;
            rec.medId = medId;
            rec.scheduled = TimeUtil.minute(when);
            rec.verification = med != null && med.observed ? Verification.NEEDS_REVIEW : Verification.NOT_REQUIRED;
            data.records.add(rec);
        }
        DoseStatus previous = rec.status;
        rec.status = status;
        rec.actionAt = TimeUtil.second(at);
        Inventory.onStatusChanged(med, previous, status);
        return rec;
    }
}
