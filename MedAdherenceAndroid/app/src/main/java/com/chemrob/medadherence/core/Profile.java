package com.chemrob.medadherence.core;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/** The patient's profile, created on first launch and shown on reports. */
public final class Profile {
    public String name = "";
    public String dateOfBirth = "";   // yyyy-MM-dd, optional
    public String sex = "";           // "Female", "Male", "Other" or ""
    public String phone = "";
    public String conditions = "";    // e.g. "Type 2 diabetes, hypertension"
    public String allergies = "";     // e.g. "Penicillin"
    public String doctor = "";        // doctor or pharmacy, free text
    public String emergencyName = "";
    public String emergencyPhone = "";
    public String caregiverName = "";  // family member or nurse who gets missed-dose alerts; "" = emergency contact
    public String caregiverPhone = "";
    public String facePhoto = "";      // enrolment photo taken with face detection, "" if none
    public double[] faceSignature;     // FaceSignature of the enrolled face, or null (older scans)
    public List<float[]> faceEmbeddings = new ArrayList<>(); // face-recognition fingerprints of the enrolled views

    /** A profile exists once the patient has entered at least a name. */
    public boolean isComplete() { return name != null && !name.trim().isEmpty(); }

    public boolean hasFace() { return hasFaceRecognition() || (faceSignature != null && faceSignature.length > 0); }

    /** True when the face was enrolled with the recognition model (not just the older geometry check). */
    public boolean hasFaceRecognition() { return faceEmbeddings != null && !faceEmbeddings.isEmpty(); }

    /** Age in whole years, or null when the date of birth is missing or in the future. */
    public Integer age(LocalDate today) {
        LocalDate dob = TimeUtil.parseDate(dateOfBirth);
        if (dob == null || dob.isAfter(today)) return null;
        return Period.between(dob, today).getYears();
    }

    /** First name for greetings ("Good morning, Asha"). */
    public String firstName() {
        String n = name == null ? "" : name.trim();
        int sp = n.indexOf(' ');
        return sp > 0 ? n.substring(0, sp) : n;
    }

    /** "Asha Devi, 64 y, Female" style one-liner. */
    public String summary(LocalDate today) {
        StringBuilder sb = new StringBuilder(name.trim());
        Integer a = age(today);
        if (a != null) sb.append(", ").append(I18n.tf("%d y", a));
        if (!sex.isEmpty()) sb.append(", ").append(I18n.t(sex));
        return sb.toString();
    }

    /** Validation for the profile form; returns an error message or null when valid. */
    public String validate(LocalDate today) {
        if (!isComplete()) return "Please enter the patient's name.";
        if (!dateOfBirth.trim().isEmpty()) {
            LocalDate dob = TimeUtil.parseDate(dateOfBirth);
            if (dob == null) return "Date of birth must look like 1960-04-23.";
            if (dob.isAfter(today)) return "Date of birth can't be in the future.";
            if (dob.isBefore(today.minusYears(130))) return "Please check the date of birth.";
        }
        if (!phone.isEmpty() && !isPhone(phone)) return "Please check the phone number.";
        if (!emergencyPhone.isEmpty() && !isPhone(emergencyPhone)) return "Please check the emergency contact's number.";
        if (!caregiverPhone.isEmpty() && !isPhone(caregiverPhone)) return "Please check the caregiver's number.";
        return null;
    }

    static boolean isPhone(String s) {
        String digits = s.replaceAll("[\\s()+-]", "");
        return digits.matches("\\d{6,15}");
    }

    /** Who gets caregiver alerts: the caregiver, or else the emergency contact. */
    public String caregiverNumber() {
        return !caregiverPhone.trim().isEmpty() ? caregiverPhone.trim() : emergencyPhone.trim();
    }

    public String caregiverLabel() {
        if (!caregiverPhone.trim().isEmpty()) return caregiverName.trim().isEmpty() ? caregiverPhone.trim() : caregiverName.trim();
        return emergencyName.trim().isEmpty() ? emergencyPhone.trim() : emergencyName.trim();
    }

    public Profile copy() {
        Profile p = new Profile();
        p.name = name; p.dateOfBirth = dateOfBirth; p.sex = sex; p.phone = phone;
        p.conditions = conditions; p.allergies = allergies; p.doctor = doctor;
        p.emergencyName = emergencyName; p.emergencyPhone = emergencyPhone;
        p.caregiverName = caregiverName; p.caregiverPhone = caregiverPhone;
        p.facePhoto = facePhoto; p.faceSignature = faceSignature == null ? null : faceSignature.clone();
        p.faceEmbeddings = new ArrayList<>();
        if (faceEmbeddings != null) for (float[] e : faceEmbeddings) p.faceEmbeddings.add(e.clone());
        return p;
    }
}
