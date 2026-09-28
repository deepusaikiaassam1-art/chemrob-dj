package com.chemrob.medadherence.core;

public final class Settings {
    public int graceMinutes = 120;       // after this an un-actioned dose counts as missed
    public int onTimeWindowMinutes = 60; // taken within +/- this = on time
    public int snoozeMinutes = 10;
    public String pharmacistPin = "0000";
    public boolean lockEditingWithPin;
    public String patientName = "";      // legacy; the name now lives in Profile
    public String theme = "system";        // "system", "light" or "dark"
    public boolean voiceGuidance = true;   // speak instructions once a dose is accepted
    public String language = "system";     // "system", "en", "hi", "bn" or "as"
    public boolean caregiverMissedAlerts = true;  // offer to tell the caregiver when a dose is missed
    public boolean caregiverDailySummary;         // evening summary to send to the caregiver
    public int summaryHour = 21;
    public String caregiverLastCheck = "";        // ISO date-time up to which missed doses were reported
}
