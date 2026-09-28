package com.chemrob.medadherence.core;

import java.time.LocalDateTime;

/** A concrete occurrence of a medication at a time. */
public final class ScheduledDose {
    public final Medication med;
    public final LocalDateTime time;

    public ScheduledDose(Medication med, LocalDateTime time) {
        this.med = med;
        this.time = time;
    }

    public String key() { return DoseKey.make(med.id, time); }
}
