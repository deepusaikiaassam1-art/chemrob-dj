package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class TimeUtil {
    private TimeUtil() {}

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    public static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    public static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    public static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    static final DateTimeFormatter KEY = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.ROOT);

    public static String date(LocalDate d) { return d.format(DATE); }
    public static String minute(LocalDateTime d) { return d.format(MINUTE); }
    public static String second(LocalDateTime d) { return d.format(SECOND); }
    public static String clock(LocalDateTime d) { return d.format(CLOCK); }

    /** Parses yyyy-MM-dd, or returns null. */
    public static LocalDate parseDate(String s) {
        try { return s == null ? null : LocalDate.parse(s.trim(), DATE); } catch (DateTimeParseException e) { return null; }
    }

    public static LocalDateTime parseMinute(String s) {
        try { return s == null ? null : LocalDateTime.parse(s, MINUTE); } catch (DateTimeParseException e) { return null; }
    }

    public static LocalDateTime parseSecond(String s) {
        try { return s == null ? null : LocalDateTime.parse(s, SECOND); } catch (DateTimeParseException e) { return null; }
    }

    /** Accepts "8", "8:00", "08:00", "0800", "8am", "8:30 pm", "21.45". Returns "HH:mm" or null. */
    public static String normalizeClock(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(".", ":").replace(" ", "");
        boolean pm = s.endsWith("pm"), am = s.endsWith("am");
        if (pm || am) s = s.substring(0, s.length() - 2);
        int h, m = 0;
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                if (p.length != 2) return null;
                h = Integer.parseInt(p[0]);
                m = Integer.parseInt(p[1]);
            } else if (s.length() == 4) {
                int hhmm = Integer.parseInt(s);
                h = hhmm / 100;
                m = hhmm % 100;
            } else {
                h = Integer.parseInt(s);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (pm || am) {
            if (h < 1 || h > 12) return null;
            if (h == 12) h = 0;
            if (pm) h += 12;
        }
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    public static LocalTime parseClock(String raw) {
        String c = normalizeClock(raw);
        return c == null ? null : LocalTime.parse(c, CLOCK);
    }
}
