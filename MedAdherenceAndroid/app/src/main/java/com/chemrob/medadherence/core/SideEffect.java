package com.chemrob.medadherence.core;

/** A side effect the patient reported in the daily check-in. */
public final class SideEffect {
    /** Symptoms offered in the check-in; the serious ones need help straight away. */
    public enum Symptom {
        DIARRHOEA("Diarrhoea", false),
        NAUSEA("Nausea or vomiting", false),
        STOMACH("Stomach pain", false),
        RASH("Rash or itching", false),
        DIZZY("Dizziness or headache", false),
        OTHER("Something else", false),
        BREATHING("Swelling of the face, lips or throat, or difficulty breathing", true),
        BLISTERS("Skin peeling or blisters", true),
        BLOODY("Severe or bloody diarrhoea", true);

        public final String label;
        public final boolean serious;

        Symptom(String label, boolean serious) { this.label = label; this.serious = serious; }

        public static Symptom of(String name) {
            for (Symptom s : values()) if (s.name().equals(name)) return s;
            return OTHER;
        }
    }

    public String at = "";        // yyyy-MM-dd HH:mm
    public Symptom symptom = Symptom.OTHER;
    public String medicines = ""; // names of the medicines being taken at the time
}
