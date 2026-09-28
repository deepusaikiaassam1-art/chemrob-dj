package com.chemrob.medadherence.core;

public final class Settings {
    public int graceMinutes = 120;       // after this an un-actioned dose counts as missed
    public int onTimeWindowMinutes = 60; // taken within +/- this = on time
    public int snoozeMinutes = 10;
    public String pharmacistPin = "0000";
    public boolean lockEditingWithPin;
    public String patientName = "";      // legacy; the name now lives in Profile
    public String theme = "system";      // "system", "light" or "dark"
}
