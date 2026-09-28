package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AdherenceCalculator {
    private AdherenceCalculator() {}

    public static final class Report {
        public LocalDateTime from, to;
        public AdherenceStats overall = new AdherenceStats("All medicines");
        public List<AdherenceStats> perMedication = new ArrayList<>();
        public int currentStreakDays;
    }

    /** Adherence over [from, now]; doses after now are ignored. */
    public static Report compute(AppData data, LocalDateTime from, LocalDateTime now) {
        Report r = new Report();
        r.from = from;
        r.to = now;
        Map<String, AdherenceStats> perMed = new LinkedHashMap<>();
        Map<String, Map<LocalDate, Boolean>> dayMed = new HashMap<>();
        Map<LocalDate, Boolean> dayAll = new HashMap<>();
        int window = data.settings.onTimeWindowMinutes;
        for (Medication m : data.medications) {
            perMed.put(m.id, new AdherenceStats(m.name));
            dayMed.put(m.id, new HashMap<>());
        }

        for (ScheduledDose dose : ScheduleEngine.doses(data, from, now.plusNanos(1))) {
            AdherenceStats s = perMed.get(dose.med.id);
            DoseStatus status = ScheduleEngine.statusOf(data, dose, now);
            if (status == DoseStatus.PENDING || status == DoseStatus.SNOOZED) {
                s.pending++;
                r.overall.pending++;
                continue;
            }
            boolean ok = status == DoseStatus.TAKEN;
            tally(s, data, dose, status, window);
            tally(r.overall, data, dose, status, window);
            LocalDate d = dose.time.toLocalDate();
            dayAll.merge(d, ok, Boolean::logicalAnd);
            dayMed.get(dose.med.id).merge(d, ok, Boolean::logicalAnd);
        }

        fillDays(r.overall, dayAll);
        for (Medication m : data.medications) {
            fillDays(perMed.get(m.id), dayMed.get(m.id));
            r.perMedication.add(perMed.get(m.id));
        }
        r.currentStreakDays = streak(dayAll, now.toLocalDate());
        return r;
    }

    private static void tally(AdherenceStats s, AppData data, ScheduledDose dose, DoseStatus status, int window) {
        s.due++;
        DoseRecord rec = data.findRecord(dose.key());
        switch (status) {
            case TAKEN:
                s.taken++;
                LocalDateTime at = rec != null && rec.actionTime() != null ? rec.actionTime() : dose.time;
                if (Math.abs(ChronoUnit.MINUTES.between(dose.time, at)) <= window) s.onTime++; else s.late++;
                break;
            case SKIPPED: s.skipped++; break;
            default: s.missed++;
        }
        if (dose.med.observed) {
            s.observedDue++;
            if (rec != null && status == DoseStatus.TAKEN
                    && (rec.verification == Verification.AUTO_VERIFIED || rec.verification == Verification.PHARMACIST_APPROVED))
                s.observedVerified++;
        }
    }

    private static void fillDays(AdherenceStats s, Map<LocalDate, Boolean> days) {
        s.daysElapsed = days.size();
        s.daysCovered = 0;
        for (boolean ok : days.values()) if (ok) s.daysCovered++;
    }

    private static int streak(Map<LocalDate, Boolean> days, LocalDate today) {
        // An unsettled today does not break the streak.
        LocalDate d = days.containsKey(today) ? today : today.minusDays(1);
        int n = 0;
        while (Boolean.TRUE.equals(days.get(d))) { n++; d = d.minusDays(1); }
        return n;
    }

    /** Plain-text summary for sharing with a pharmacist or doctor. */
    public static String toText(AppData data, Report r) {
        StringBuilder sb = new StringBuilder("Medication adherence report\n");
        if (!data.settings.patientName.isEmpty()) sb.append("Patient: ").append(data.settings.patientName).append('\n');
        sb.append("Period: ").append(TimeUtil.date(r.from.toLocalDate())).append(" to ").append(TimeUtil.minute(r.to)).append("\n\n");
        append(sb, r.overall);
        sb.append("Current streak: ").append(r.currentStreakDays).append(" day(s)\n\n");
        for (AdherenceStats s : r.perMedication) append(sb, s);
        return sb.toString();
    }

    private static void append(StringBuilder sb, AdherenceStats s) {
        sb.append(s.label).append(" - ").append(s.category()).append('\n');
        sb.append(String.format(Locale.ROOT, "  Doses due %d: taken %d (on time %d, late %d), skipped %d, missed %d%n",
                s.due, s.taken, s.onTime, s.late, s.skipped, s.missed));
        sb.append(String.format(Locale.ROOT, "  Dose adherence %.1f%% | timing %.1f%% | days covered %.1f%%%n",
                s.takingPercent(), s.timingPercent(), s.daysCoveredPercent()));
        if (s.observedDue > 0)
            sb.append(String.format(Locale.ROOT, "  Observed doses verified: %d/%d (%.1f%%)%n", s.observedVerified, s.observedDue, s.verifiedPercent()));
    }

    /** One row per settled dose, for spreadsheet analysis. */
    public static String doseLogCsv(AppData data, LocalDateTime from, LocalDateTime now) {
        StringBuilder sb = new StringBuilder("medicine,dose,scheduled,status,action_at,minutes_from_schedule,observed,verification,evidence_files\n");
        for (ScheduledDose dose : ScheduleEngine.doses(data, from, now.plusNanos(1))) {
            DoseStatus status = ScheduleEngine.statusOf(data, dose, now);
            if (status == DoseStatus.PENDING) continue;
            DoseRecord rec = data.findRecord(dose.key());
            String delta = rec != null && status == DoseStatus.TAKEN && rec.actionTime() != null
                    ? String.valueOf(ChronoUnit.MINUTES.between(dose.time, rec.actionTime())) : "";
            String ver = rec != null ? rec.verification.name() : dose.med.observed ? "NONE" : "NOT_REQUIRED";
            sb.append(String.join(",", csv(dose.med.name), csv(dose.med.dose), TimeUtil.minute(dose.time), status.name(),
                    rec != null ? rec.actionAt : "", delta, dose.med.observed ? "yes" : "no", ver,
                    String.valueOf(rec != null ? rec.evidence.size() : 0))).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        return s != null && (s.contains(",") || s.contains("\"")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
