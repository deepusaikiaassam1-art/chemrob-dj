package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fast regimen entry: frequency shorthands (OD, BD, TDS, ...) and bulk import, one medicine per line:
 *   Name | Dose | Times or frequency | Duration | Start | Observed | Instructions | Stock | Units per dose
 * e.g.
 *   Metformin | 500 mg, 1 tab | BD | 30 | today | no | after food | 60 | 1
 *   Methotrexate | 7.5 mg | WEEKLY 09:00 | 12w | today | no |
 * Only the name is required. The separator is '|' if present on the line, otherwise ','.
 */
public final class RegimenParser {
    private RegimenParser() {}

    public static final class Frequency {
        public final String code, label;
        public final List<String> times;
        public final int everyNDays;

        Frequency(String code, String label, int everyNDays, String... times) {
            this.code = code;
            this.label = label;
            this.everyNDays = everyNDays;
            this.times = Arrays.asList(times);
        }
    }

    public static final List<Frequency> PRESETS = Arrays.asList(
            new Frequency("OD", "Once daily", 1, "08:00"),
            new Frequency("BD", "Twice daily", 1, "08:00", "20:00"),
            new Frequency("TDS", "Three times daily", 1, "08:00", "14:00", "20:00"),
            new Frequency("QID", "Four times daily", 1, "06:00", "12:00", "18:00", "22:00"),
            new Frequency("HS", "At bedtime", 1, "22:00"),
            new Frequency("Q8H", "Every 8 hours", 1, "06:00", "14:00", "22:00"),
            new Frequency("WEEKLY", "Once weekly", 7, "08:00"));

    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        String[][] a = {
                {"OD", "OD"}, {"QD", "OD"}, {"DAILY", "OD"}, {"ONCE", "OD"}, {"1X", "OD"},
                {"BD", "BD"}, {"BID", "BD"}, {"TWICE", "BD"}, {"2X", "BD"}, {"Q12H", "BD"},
                {"TDS", "TDS"}, {"TID", "TDS"}, {"3X", "TDS"},
                {"QID", "QID"}, {"QDS", "QID"}, {"4X", "QID"}, {"Q6H", "QID"},
                {"HS", "HS"}, {"NOCTE", "HS"}, {"BEDTIME", "HS"},
                {"Q8H", "Q8H"},
                {"WEEKLY", "WEEKLY"}, {"QW", "WEEKLY"}, {"ONCEWEEKLY", "WEEKLY"}};
        for (String[] p : a) ALIASES.put(p[0], p[1]);
    }

    public static Frequency findPreset(String codeOrAlias) {
        if (codeOrAlias == null) return null;
        String code = ALIASES.get(codeOrAlias.trim().replace(" ", "").toUpperCase(Locale.ROOT));
        if (code == null) return null;
        for (Frequency f : PRESETS) if (f.code.equals(code)) return f;
        return null;
    }

    /** Result of parsing a times field. */
    public static final class Times {
        public final List<String> times = new ArrayList<>();
        public int everyNDays = 1;
        public String error;
    }

    /** Parses "08:00 20:00", "8am;8pm", "BD", "WEEKLY 09:00", "Q2D 08:00". */
    public static Times parseTimes(String raw) {
        Times r = new Times();
        if (raw == null || raw.trim().isEmpty()) { r.error = "No time given"; return r; }
        Frequency preset = findPreset(raw);
        if (preset != null) {
            r.times.addAll(preset.times);
            r.everyNDays = preset.everyNDays;
            return r;
        }
        for (String tok : raw.trim().split("[\\s;/,+]+")) {
            if (tok.isEmpty()) continue;
            String up = tok.toUpperCase(Locale.ROOT);
            if (up.equals("WEEKLY")) { r.everyNDays = 7; continue; }
            if (up.length() > 2 && up.charAt(0) == 'Q' && up.endsWith("D")) {
                try {
                    int n = Integer.parseInt(up.substring(1, up.length() - 1));
                    if (n > 0) { r.everyNDays = n; continue; }
                } catch (NumberFormatException ignored) { }
            }
            String c = TimeUtil.normalizeClock(tok);
            if (c == null) { r.error = "Cannot read time '" + tok + "'"; r.times.clear(); return r; }
            if (!r.times.contains(c)) r.times.add(c);
        }
        if (r.times.isEmpty()) { r.error = "No time given"; return r; }
        r.times.sort(null);
        return r;
    }

    /** "10", "10d", "2w", "3m", "6 months", "ongoing". Returns days (0 = ongoing) or null if unreadable. */
    public static Integer parseDuration(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.equals("ongoing") || s.equals("continue") || s.equals("long-term") || s.equals("chronic") || s.equals("0")) return 0;
        int mult = 1;
        if (s.endsWith("days")) s = s.substring(0, s.length() - 4);
        else if (s.endsWith("weeks")) { mult = 7; s = s.substring(0, s.length() - 5); }
        else if (s.endsWith("months")) { mult = 30; s = s.substring(0, s.length() - 6); }
        else if (s.endsWith("d")) s = s.substring(0, s.length() - 1);
        else if (s.endsWith("w")) { mult = 7; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 30; s = s.substring(0, s.length() - 1); }
        try {
            int n = Integer.parseInt(s);
            return n < 0 ? null : n * mult;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean parseYesNo(String raw) {
        if (raw == null) return false;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "y": case "yes": case "true": case "1": case "dot": case "observed": case "observe": return true;
            default: return false;
        }
    }

    /** Accepts "30", "0.5", "0,5". Returns null when unreadable. */
    public static Double parseNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Double.parseDouble(raw.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    public static final class ImportResult {
        public final List<Medication> medications = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    public static ImportResult importText(String text, LocalDate today, String addedBy) {
        ImportResult result = new ImportResult();
        if (text == null) return result;
        String[] lines = text.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(line.contains("|") ? "\\|" : ",", -1);
            for (int k = 0; k < f.length; k++) f[k] = f[k].trim();
            if (i == 0 && f[0].equalsIgnoreCase("name")) continue; // header row
            String where = "Line " + (i + 1) + ": ";

            Medication m = new Medication();
            m.name = field(f, 0);
            m.dose = field(f, 1);
            m.instructions = field(f, 6);
            m.addedBy = addedBy;
            if (m.name.isEmpty()) { result.errors.add(where + "missing medicine name"); continue; }

            Times t = parseTimes(field(f, 2).isEmpty() ? "OD" : field(f, 2));
            if (t.error != null) { result.errors.add(where + t.error); continue; }
            m.times = t.times;
            m.everyNDays = t.everyNDays;

            Integer days = parseDuration(field(f, 3));
            if (days == null) { result.errors.add(where + "cannot read duration '" + field(f, 3) + "'"); continue; }
            m.durationDays = days;

            String start = field(f, 4);
            LocalDate sd;
            if (start.isEmpty() || start.equalsIgnoreCase("today")) sd = today;
            else if (start.equalsIgnoreCase("tomorrow")) sd = today.plusDays(1);
            else sd = TimeUtil.parseDate(start);
            if (sd == null) { result.errors.add(where + "start date must be yyyy-MM-dd, 'today' or 'tomorrow'"); continue; }
            m.startDate = TimeUtil.date(sd);

            m.observed = parseYesNo(field(f, 5));

            if (!field(f, 7).isEmpty()) {
                Double stock = parseNumber(field(f, 7));
                if (stock == null || stock < 0) { result.errors.add(where + "stock must be a number of units, e.g. 30"); continue; }
                m.stock = stock;
                if (!field(f, 8).isEmpty()) {
                    Double per = parseNumber(field(f, 8));
                    if (per == null || per <= 0) { result.errors.add(where + "units per dose must be a positive number, e.g. 1 or 0.5"); continue; }
                    m.unitsPerDose = per;
                }
            }
            result.medications.add(m);
        }
        return result;
    }

    private static String field(String[] f, int k) { return k < f.length ? f[k] : ""; }

    /** Inverse of importText, so a regimen can be moved to another phone. */
    public static String export(List<Medication> meds) {
        StringBuilder sb = new StringBuilder("# Name | Dose | Times | Duration days (0 = ongoing) | Start | Observed | Instructions | Stock | Units per dose\n");
        for (Medication m : meds) {
            String times = (m.everyNDays > 1 ? "Q" + m.everyNDays + "D " : "") + String.join(" ", m.times);
            String line = String.join(" | ", m.name, m.dose, times, String.valueOf(m.durationDays), m.startDate,
                    m.observed ? "yes" : "no", m.instructions,
                    m.tracksStock() ? num(m.stock) : "", m.tracksStock() ? num(m.unitsPerDose) : "");
            sb.append(line.replaceAll("[\\s|]+$", "")).append('\n');
        }
        return sb.toString();
    }

    static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
