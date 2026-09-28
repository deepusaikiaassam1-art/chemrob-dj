package com.chemrob.medadherence.core;

import java.util.Locale;

/** Wording of the emergency SMS and the automated voice message. */
public final class Emergency {
    private Emergency() {}

    /** Seconds the patient has to cancel before the call and SMS go out. */
    public static final int COUNTDOWN_SECONDS = 5;

    /** The SOS text message, in the app's language. */
    public static String smsText(Profile p, Double lat, Double lon) {
        String name = p.isComplete() ? p.name.trim() : I18n.t("The patient");
        StringBuilder sb = new StringBuilder(I18n.tf("EMERGENCY: %s needs help now (sent by the MedAdherence app).", name));
        if (!p.conditions.trim().isEmpty()) sb.append(' ').append(I18n.tf("Conditions: %s.", p.conditions.trim()));
        if (!p.allergies.trim().isEmpty()) sb.append(' ').append(I18n.tf("Allergies: %s.", p.allergies.trim()));
        if (lat != null && lon != null)
            sb.append(' ').append(I18n.tf("Location: %s", String.format(Locale.ROOT, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)));
        else sb.append(' ').append(I18n.t("Location not available."));
        if (!p.phone.trim().isEmpty()) sb.append(' ').append(I18n.tf("Patient's phone: %s.", p.phone.trim()));
        return sb.toString();
    }

    /** Spoken through the speaker during the call, in the app's language. */
    public static String voiceText(Profile p) { return voiceText(p, false); }

    /** @param english speak English (the phone has no voice for the app's language) */
    public static String voiceText(Profile p, boolean english) {
        String name = p.isComplete() ? p.name.trim() : tr("the patient", english);
        StringBuilder sb = new StringBuilder(tr("This is an automated emergency call from the Med Adherence app.", english))
                .append(' ').append(String.format(Locale.ROOT, tr("%s needs help urgently.", english), name)).append(' ');
        if (!p.conditions.trim().isEmpty())
            sb.append(String.format(Locale.ROOT, tr("%s has %s.", english), name, p.conditions.trim())).append(' ');
        sb.append(tr("Please call back or go to them now. A text message with their location has been sent to you.", english));
        return sb.toString();
    }

    private static String tr(String en, boolean english) { return english ? en : I18n.t(en); }

    /** Number to call and text: the emergency contact. Returns null when none is set. */
    public static String number(Profile p) {
        String n = p.emergencyPhone == null ? "" : p.emergencyPhone.replaceAll("[\\s()-]", "");
        return n.isEmpty() ? null : n;
    }
}
