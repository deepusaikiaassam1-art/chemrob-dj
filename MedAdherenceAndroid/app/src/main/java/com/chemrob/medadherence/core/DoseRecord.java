package com.chemrob.medadherence.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** What the patient did for one scheduled dose. Only doses with an action are stored. */
public final class DoseRecord {
    public String doseKey = "";
    public String medId = "";
    public String scheduled = "";   // yyyy-MM-dd HH:mm
    public DoseStatus status = DoseStatus.PENDING;
    public String actionAt = "";    // yyyy-MM-dd HH:mm:ss
    public Verification verification = Verification.NOT_REQUIRED;
    public double livenessScore;    // 0..1 from the observation session
    public double presenceScore;    // 0..1 from the observation session
    public List<String> evidence = new ArrayList<>(); // JPEG file paths
    public String note = "";

    public LocalDateTime scheduledTime() { return TimeUtil.parseMinute(scheduled); }
    public LocalDateTime actionTime() { return TimeUtil.parseSecond(actionAt); }
}
