package com.chemrob.medadherence.core;

import java.util.Locale;

/** Wording of the emergency SMS and the automated voice message. */
public final class Emergency {
    private Emergency() {}

    /** Seconds the patient has to cancel before the call and SMS go out. */
    public static final int COUNTDOWN_SECONDS = 5;

    public static String smsText(Profile p, Double lat, Double lon) {
        String name = p.isComplete() ? p.name.trim() : "The patient";
        StringBuilder sb = new StringBuilder("EMERGENCY: ").append(name).append(" needs help now (sent by the MedAdherence app).");
        if (!p.conditions.trim().isEmpty()) sb.append(" Conditions: ").append(p.conditions.trim()).append('.');
        if (!p.allergies.trim().isEmpty()) sb.append(" Allergies: ").append(p.allergies.trim()).append('.');
        if (lat != null && lon != null)
            sb.append(String.format(Locale.ROOT, " Location: https://maps.google.com/?q=%.6f,%.6f", lat, lon));
        else sb.append(" Location not available.");
        if (!p.phone.trim().isEmpty()) sb.append(" Patient's phone: ").append(p.phone.trim()).append('.');
        return sb.toString();
    }

    /** Spoken through the speaker during the call. */
    public static String voiceText(Profile p) {
        String name = p.isComplete() ? p.name.trim() : "the patient";
        StringBuilder sb = new StringBuilder("This is an automated emergency call from the Med Adherence app. ")
                .append(name).append(" needs help urgently. ");
        if (!p.conditions.trim().isEmpty()) sb.append(name).append(" has ").append(p.conditions.trim()).append(". ");
        sb.append("Please call back or go to them now. A text message with their location has been sent to you.");
        return sb.toString();
    }

    /** Number to call and text: the emergency contact. Returns null when none is set. */
    public static String number(Profile p) {
        String n = p.emergencyPhone == null ? "" : p.emergencyPhone.replaceAll("[\\s()-]", "");
        return n.isEmpty() ? null : n;
    }
}
