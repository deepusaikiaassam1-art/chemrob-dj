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
                        .put("stock", m.stock).put("unitsPerDose", m.unitsPerDose).put("refillAlertDays", m.refillAlertDays).put("photo", m.photo)
                        .put("form", m.form).put("side", m.side).put("leftover", m.leftover);
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
            JSONArray appts = new JSONArray();
            for (Appointment a : d.appointments)
                appts.put(new JSONObject().put("id", a.id).put("when", a.when).put("doctor", a.doctor)
                        .put("place", a.place).put("purpose", a.purpose).put("done", a.done));
            root.put("appointments", appts);
            JSONArray effects = new JSONArray();
            for (SideEffect e : d.sideEffects)
                effects.put(new JSONObject().put("at", e.at).put("symptom", e.symptom.name()).put("medicines", e.medicines));
            root.put("sideEffects", effects);
            Profile pr = d.profile;
            root.put("profile", new JSONObject().put("name", pr.name).put("dateOfBirth", pr.dateOfBirth)
                    .put("sex", pr.sex).put("phone", pr.phone).put("conditions", pr.conditions)
                    .put("allergies", pr.allergies).put("doctor", pr.doctor)
                    .put("emergencyName", pr.emergencyName).put("emergencyPhone", pr.emergencyPhone)
                    .put("caregiverName", pr.caregiverName).put("caregiverPhone", pr.caregiverPhone)
                    .put("pharmacistPhone", pr.pharmacistPhone)
                    .put("facePhoto", pr.facePhoto).put("faceEmbeddings", embeddings(pr.faceEmbeddings)).put("faceSignature", pr.faceSignature == null ? null : doubles(pr.faceSignature)));
            root.put("settings", new JSONObject().put("graceMinutes", s.graceMinutes)
                    .put("onTimeWindowMinutes", s.onTimeWindowMinutes).put("snoozeMinutes", s.snoozeMinutes)
                    .put("pharmacistPin", s.pharmacistPin).put("lockEditingWithPin", s.lockEditingWithPin)
                    .put("patientName", s.patientName).put("theme", s.theme).put("voiceGuidance", s.voiceGuidance)
                    .put("language", s.language).put("caregiverMissedAlerts", s.caregiverMissedAlerts)
                    .put("caregiverDailySummary", s.caregiverDailySummary).put("summaryHour", s.summaryHour)
                    .put("caregiverLastCheck", s.caregiverLastCheck).put("lastCheckIn", s.lastCheckIn));
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
            m.photo = o.optString("photo", "");
            m.form = o.optString("form", "tablet");
            m.side = o.optString("side", "");
            m.leftover = o.optDouble("leftover", -1);
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
        JSONArray appts = root.optJSONArray("appointments");
        for (int i = 0; appts != null && i < appts.length(); i++) {
            JSONObject o = appts.getJSONObject(i);
            Appointment a = new Appointment();
            a.id = o.optString("id", a.id);
            a.when = o.optString("when", "");
            a.doctor = o.optString("doctor", "");
            a.place = o.optString("place", "");
            a.purpose = o.optString("purpose", "");
            a.done = o.optBoolean("done", false);
            d.appointments.add(a);
        }
        JSONArray effects = root.optJSONArray("sideEffects");
        for (int i = 0; effects != null && i < effects.length(); i++) {
            JSONObject o = effects.getJSONObject(i);
            SideEffect e = new SideEffect();
            e.at = o.optString("at", "");
            e.symptom = SideEffect.Symptom.of(o.optString("symptom", ""));
            e.medicines = o.optString("medicines", "");
            d.sideEffects.add(e);
        }
        JSONObject p = root.optJSONObject("profile");
        if (p != null) {
            Profile pr = d.profile;
            pr.name = p.optString("name", "");
            pr.dateOfBirth = p.optString("dateOfBirth", "");
            pr.sex = p.optString("sex", "");
            pr.phone = p.optString("phone", "");
            pr.conditions = p.optString("conditions", "");
            pr.allergies = p.optString("allergies", "");
            pr.doctor = p.optString("doctor", "");
            pr.emergencyName = p.optString("emergencyName", "");
            pr.emergencyPhone = p.optString("emergencyPhone", "");
            pr.caregiverName = p.optString("caregiverName", "");
            pr.caregiverPhone = p.optString("caregiverPhone", "");
            pr.pharmacistPhone = p.optString("pharmacistPhone", "");
            pr.facePhoto = p.optString("facePhoto", "");
            JSONArray embs = p.optJSONArray("faceEmbeddings");
            for (int i = 0; embs != null && i < embs.length(); i++) {
                JSONArray e = embs.optJSONArray(i);
                if (e == null || e.length() == 0) continue;
                float[] v = new float[e.length()];
                for (int k = 0; k < v.length; k++) v[k] = (float) e.optDouble(k, 0);
                pr.faceEmbeddings.add(v);
            }
            JSONArray sig = p.optJSONArray("faceSignature");
            if (sig != null && sig.length() > 0) {
                pr.faceSignature = new double[sig.length()];
                for (int i = 0; i < sig.length(); i++) pr.faceSignature[i] = sig.optDouble(i, 0);
            }
        }
        JSONObject s = root.optJSONObject("settings");
        if (s != null) {
            d.settings.graceMinutes = s.optInt("graceMinutes", 120);
            d.settings.onTimeWindowMinutes = s.optInt("onTimeWindowMinutes", 60);
            d.settings.snoozeMinutes = s.optInt("snoozeMinutes", 10);
            d.settings.pharmacistPin = s.optString("pharmacistPin", "0000");
            d.settings.lockEditingWithPin = s.optBoolean("lockEditingWithPin", false);
            d.settings.patientName = s.optString("patientName", "");
            d.settings.theme = s.optString("theme", "system");
            d.settings.voiceGuidance = s.optBoolean("voiceGuidance", true);
            d.settings.language = s.optString("language", "system");
            d.settings.caregiverMissedAlerts = s.optBoolean("caregiverMissedAlerts", true);
            d.settings.caregiverDailySummary = s.optBoolean("caregiverDailySummary", false);
            d.settings.summaryHour = Math.max(0, Math.min(23, s.optInt("summaryHour", 21)));
            d.settings.caregiverLastCheck = s.optString("caregiverLastCheck", "");
            d.settings.lastCheckIn = s.optString("lastCheckIn", "");
        }
        if (!d.profile.isComplete() && !d.settings.patientName.isEmpty()) d.profile.name = d.settings.patientName;
        return d;
    }

    private static JSONArray embeddings(List<float[]> list) throws JSONException {
        JSONArray a = new JSONArray();
        if (list != null) for (float[] e : list) {
            JSONArray row = new JSONArray();
            for (float f : e) row.put((double) Math.round(f * 1e5) / 1e5);
            a.put(row);
        }
        return a;
    }

    private static JSONArray doubles(double[] v) throws JSONException {
        JSONArray a = new JSONArray();
        for (double d : v) a.put(d);
        return a;
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
