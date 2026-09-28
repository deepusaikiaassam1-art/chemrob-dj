package com.chemrob.medadherence.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** AppData <-> JSON. Unknown or missing fields fall back to defaults so old files keep loading. */
public final class JsonCodec {
    private JsonCodec() {}

    public static String toJson(AppData d) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray meds = new JSONArray();
            for (Medication m : d.medications) {
                JSONObject o = new JSONObject();
                o.put("id", m.id).put("name", m.name).put("dose", m.dose).put("instructions", m.instructions)
                        .put("times", new JSONArray(m.times)).put("startDate", m.startDate)
                        .put("durationDays", m.durationDays).put("everyNDays", m.everyNDays)
                        .put("observed", m.observed).put("pauses", new JSONArray(m.pauses)).put("addedBy", m.addedBy)
                        .put("stock", m.stock).put("unitsPerDose", m.unitsPerDose).put("refillAlertDays", m.refillAlertDays);
                meds.put(o);
            }
            root.put("medications", meds);
            JSONArray recs = new JSONArray();
            for (DoseRecord r : d.records) {
                JSONObject o = new JSONObject();
                o.put("doseKey", r.doseKey).put("medId", r.medId).put("scheduled", r.scheduled)
                        .put("status", r.status.name()).put("actionAt", r.actionAt)
                        .put("verification", r.verification.name()).put("liveness", r.livenessScore)
                        .put("presence", r.presenceScore).put("evidence", new JSONArray(r.evidence)).put("note", r.note);
                recs.put(o);
            }
            root.put("records", recs);
            Settings s = d.settings;
            root.put("settings", new JSONObject().put("graceMinutes", s.graceMinutes)
                    .put("onTimeWindowMinutes", s.onTimeWindowMinutes).put("snoozeMinutes", s.snoozeMinutes)
                    .put("pharmacistPin", s.pharmacistPin).put("lockEditingWithPin", s.lockEditingWithPin)
                    .put("patientName", s.patientName));
            return root.toString(1);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static AppData fromJson(String json) throws JSONException {
        AppData d = new AppData();
        JSONObject root = new JSONObject(json);
        JSONArray meds = root.optJSONArray("medications");
        for (int i = 0; meds != null && i < meds.length(); i++) {
            JSONObject o = meds.getJSONObject(i);
            Medication m = new Medication();
            m.id = o.optString("id", m.id);
            m.name = o.optString("name", "");
            m.dose = o.optString("dose", "");
            m.instructions = o.optString("instructions", "");
            m.times = strings(o.optJSONArray("times"));
            m.startDate = o.optString("startDate", "");
            m.durationDays = o.optInt("durationDays", 0);
            m.everyNDays = o.optInt("everyNDays", 1);
            m.observed = o.optBoolean("observed", false);
            m.pauses = strings(o.optJSONArray("pauses"));
            m.addedBy = o.optString("addedBy", "patient");
            m.stock = o.optDouble("stock", -1);
            m.unitsPerDose = o.optDouble("unitsPerDose", 1);
            m.refillAlertDays = o.optInt("refillAlertDays", 5);
            d.medications.add(m);
        }
        JSONArray recs = root.optJSONArray("records");
        for (int i = 0; recs != null && i < recs.length(); i++) {
            JSONObject o = recs.getJSONObject(i);
            DoseRecord r = new DoseRecord();
            r.doseKey = o.optString("doseKey", "");
            r.medId = o.optString("medId", "");
            r.scheduled = o.optString("scheduled", "");
            r.status = enumOr(DoseStatus.class, o.optString("status"), DoseStatus.PENDING);
            r.actionAt = o.optString("actionAt", "");
            r.verification = enumOr(Verification.class, o.optString("verification"), Verification.NOT_REQUIRED);
            r.livenessScore = o.optDouble("liveness", 0);
            r.presenceScore = o.optDouble("presence", 0);
            r.evidence = strings(o.optJSONArray("evidence"));
            r.note = o.optString("note", "");
            d.records.add(r);
        }
        JSONObject s = root.optJSONObject("settings");
        if (s != null) {
            d.settings.graceMinutes = s.optInt("graceMinutes", 120);
            d.settings.onTimeWindowMinutes = s.optInt("onTimeWindowMinutes", 60);
            d.settings.snoozeMinutes = s.optInt("snoozeMinutes", 10);
            d.settings.pharmacistPin = s.optString("pharmacistPin", "0000");
            d.settings.lockEditingWithPin = s.optBoolean("lockEditingWithPin", false);
            d.settings.patientName = s.optString("patientName", "");
        }
        return d;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try { return Enum.valueOf(type, name); } catch (RuntimeException e) { return fallback; }
    }
}
