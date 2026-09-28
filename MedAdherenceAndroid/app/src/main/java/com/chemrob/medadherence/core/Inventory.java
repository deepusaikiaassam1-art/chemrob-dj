package com.chemrob.medadherence.core;

import java.time.LocalDateTime;
import java.util.Locale;

/** Stock on hand and refill warnings. Running out is a common reason for missed doses. */
public final class Inventory {
    private Inventory() {}

    /** Average doses per day, e.g. 2 for BD, 1/7 for weekly. */
    public static double dosesPerDay(Medication m) {
        return m == null || m.times.isEmpty() ? 0 : (double) m.times.size() / Math.max(1, m.everyNDays);
    }

    /** Whole doses the stock covers, or null when not tracked. */
    public static Integer dosesLeft(Medication m) {
        if (m == null || !m.tracksStock()) return null;
        double per = m.unitsPerDose > 0 ? m.unitsPerDose : 1;
        return (int) Math.floor(m.stock / per + 1e-6);
    }

    /** Days the stock lasts at the prescribed rate, or null when not tracked. */
    public static Double daysLeft(Medication m) {
        Integer left = dosesLeft(m);
        double perDay = dosesPerDay(m);
        return left == null || perDay <= 0 ? null : left / perDay;
    }

    /** Doses still scheduled from now to the end of a fixed course; null if ongoing. */
    public static Integer remainingCourseDoses(Medication m, LocalDateTime now) {
        if (m == null || m.end() == null) return null;
        return ScheduleEngine.doses(m, now, m.end().plusDays(1).atStartOfDay()).size();
    }

    /** Stock tracked, will run short of the prescription, and lasts fewer than refillAlertDays days. */
    public static boolean needsRefill(Medication m, LocalDateTime now) {
        Integer left = dosesLeft(m);
        if (left == null || !m.isActive()) return false;
        Integer remaining = remainingCourseDoses(m, now);
        if (remaining != null && left >= remaining) return false;
        Double days = daysLeft(m);
        return days != null && days < m.refillAlertDays;
    }

    /** e.g. "7 left (~3 days)"; empty if not tracked. */
    public static String label(Medication m) {
        if (dosesLeft(m) == null) return "";
        String units = m.stock == Math.floor(m.stock) ? String.valueOf((long) m.stock) : String.format(Locale.ROOT, "%.1f", m.stock);
        Double days = daysLeft(m);
        if (days == null) return units + " left";
        long d = (long) Math.floor(days);
        return units + " left (~" + d + " day" + (d == 1 ? "" : "s") + ")";
    }

    /** Taking a dose uses stock; changing a taken dose to something else returns it. */
    public static void onStatusChanged(Medication m, DoseStatus previous, DoseStatus current) {
        if (m == null || !m.tracksStock() || previous == current) return;
        double per = m.unitsPerDose > 0 ? m.unitsPerDose : 1;
        if (current == DoseStatus.TAKEN) m.stock = Math.max(0, m.stock - per);
        else if (previous == DoseStatus.TAKEN) m.stock += per;
    }

    public static void refill(Medication m, double units) {
        if (m == null || units <= 0) return;
        m.stock = Math.max(0, m.stock) + units;
    }
}
