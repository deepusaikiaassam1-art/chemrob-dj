package com.chemrob.medadherence.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/** Stable id of one scheduled dose: "medId|yyyyMMddHHmm". */
public final class DoseKey {
    private DoseKey() {}

    public static String make(String medId, LocalDateTime t) { return medId + "|" + t.format(TimeUtil.KEY); }

    public static String medId(String key) {
        int bar = key == null ? -1 : key.lastIndexOf('|');
        return bar <= 0 ? null : key.substring(0, bar);
    }

    public static LocalDateTime time(String key) {
        int bar = key == null ? -1 : key.lastIndexOf('|');
        if (bar <= 0) return null;
        try { return LocalDateTime.parse(key.substring(bar + 1), TimeUtil.KEY); } catch (DateTimeParseException e) { return null; }
    }
}
