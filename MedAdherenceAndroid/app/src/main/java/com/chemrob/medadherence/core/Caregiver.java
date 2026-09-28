package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.chemrob.medadherence.core.I18n.t;
import static com.chemrob.medadherence.core.I18n.tf;

/**
 * Caregiver alerts: finds doses that have just become missed, and writes the messages to send to
 * the caregiver (a family member or nurse). The app has no SMS permission, so the patient's phone
 * shows a notification and one tap opens WhatsApp or the messaging app with the text filled in.
 */
public final class Caregiver {
    private Caregiver() {}

    /** When a dose counts as missed. */
    public static LocalDateTime deadline(AppData d, ScheduledDose dose) {
        return dose.time.plusMinutes(d.settings.graceMinutes);
    }

    /** Doses whose missed-deadline passed in (since, now] and that are still not taken or skipped. */
    public static List<ScheduledDose> newlyMissed(AppData d, LocalDateTime since, LocalDateTime now) {
        List<ScheduledDose> out = new ArrayList<>();
        int grace = d.settings.graceMinutes;
        for (ScheduledDose x : ScheduleEngine.doses(d, since.minusMinutes(grace), now.minusMinutes(grace).plusNanos(1))) {
            LocalDateTime dl = deadline(d, x);
            if (dl.isAfter(since) && !dl.isAfter(now) && ScheduleEngine.statusOf(d, x, now) == DoseStatus.MISSED) out.add(x);
        }
        return out;
    }

    /** The next moment a dose could become missed, after {@code now}; null if none within a week. */
    public static LocalDateTime nextCheck(AppData d, LocalDateTime now) {
        int grace = d.settings.graceMinutes;
        for (ScheduledDose x : ScheduleEngine.doses(d, now.minusMinutes(grace), now.plusDays(7))) {
            LocalDateTime dl = deadline(d, x);
            if (dl.isAfter(now)) return dl.plusMinutes(1);
        }
        return null;
    }

    /** The next daily-summary time after {@code now}. */
    public static LocalDateTime nextSummary(AppData d, LocalDateTime now) {
        LocalDateTime at = now.toLocalDate().atTime(d.settings.summaryHour, 0);
        return at.isAfter(now) ? at : at.plusDays(1);
    }

    public static String missedMessage(AppData d, List<ScheduledDose> missed) {
        String who = d.profile.firstName().isEmpty() ? t("The patient") : d.profile.firstName();
        StringBuilder sb = new StringBuilder();
        for (ScheduledDose x : missed) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x.med.name);
            if (!x.med.dose.isEmpty()) sb.append(' ').append(x.med.dose);
            sb.append(" (").append(TimeUtil.clock(x.time)).append(')');
        }
        return tf("MedAdherence: %s has missed %s. Please check on them.", who, sb.toString());
    }

    /** "Today: 5 of 6 doses taken. Missed: ..." */
    public static String dailySummary(AppData d, LocalDate day, LocalDateTime now) {
        LocalDateTime end = day.plusDays(1).atStartOfDay().isAfter(now) ? now : day.plusDays(1).atStartOfDay();
        int due = 0, taken = 0;
        List<String> missed = new ArrayList<>();
        for (ScheduledDose x : ScheduleEngine.doses(d, day.atStartOfDay(), end)) {
            DoseStatus s = ScheduleEngine.statusOf(d, x, now);
            if (s == DoseStatus.PENDING || s == DoseStatus.SNOOZED) continue;
            due++;
            if (s == DoseStatus.TAKEN) taken++;
            else if (s == DoseStatus.MISSED) missed.add(x.med.name + " " + TimeUtil.clock(x.time));
        }
        String who = d.profile.firstName().isEmpty() ? t("The patient") : d.profile.firstName();
        StringBuilder sb = new StringBuilder(tf("MedAdherence daily summary for %s, %s:", who, TimeUtil.date(day)));
        sb.append('\n');
        if (due == 0) sb.append(t("No doses were due."));
        else sb.append(tf("%d of %d doses taken.", taken, due));
        if (!missed.isEmpty()) sb.append('\n').append(t("Missed:")).append(' ').append(String.join(", ", missed));
        Appointment next = Appointment.next(d.appointments, now);
        if (next != null && next.time().isBefore(now.plusDays(3)))
            sb.append('\n').append(tf("Doctor visit: %s", TimeUtil.minute(next.time())));
        return sb.toString();
    }

    /** Digits and a leading + only, for wa.me links and smsto: URIs. */
    public static String dialable(String phone) {
        if (phone == null) return "";
        String p = phone.trim();
        String digits = p.replaceAll("[^0-9]", "");
        return p.startsWith("+") ? "+" + digits : digits;
    }

    /**
     * Number in international form for WhatsApp (no +). Ten-digit Indian mobile numbers without a
     * country code get 91 in front.
     */
    public static String whatsappNumber(String phone) {
        String d = dialable(phone);
        if (d.startsWith("+")) return d.substring(1);
        if (d.startsWith("0") && d.length() == 11) d = d.substring(1);
        return d.length() == 10 ? "91" + d : d;
    }
}
