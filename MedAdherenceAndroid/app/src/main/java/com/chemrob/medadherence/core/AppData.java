package com.chemrob.medadherence.core;

import java.util.ArrayList;
import java.util.List;

/** Everything persisted to disk. */
public final class AppData {
    public List<Medication> medications = new ArrayList<>();
    public List<DoseRecord> records = new ArrayList<>();
    public Settings settings = new Settings();
    public Profile profile = new Profile();
    public List<Appointment> appointments = new ArrayList<>();

    public Medication findMed(String id) {
        for (Medication m : medications) if (m.id.equals(id)) return m;
        return null;
    }

    public DoseRecord findRecord(String doseKey) {
        for (DoseRecord r : records) if (r.doseKey.equals(doseKey)) return r;
        return null;
    }
}
