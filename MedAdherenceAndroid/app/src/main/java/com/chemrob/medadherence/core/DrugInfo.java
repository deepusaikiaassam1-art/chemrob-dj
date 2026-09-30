package com.chemrob.medadherence.core;

import java.util.Locale;

/**
 * A small built-in drug table, matched on the generic name (and a few common Indian brand names)
 * typed for a medicine: which drug class it belongs to, whether it is an antimicrobial, allergy
 * cross-checks, and advice on timing with food and other medicines.
 *
 * It covers common drugs only and is a safety prompt, not a substitute for the pharmacist; the
 * app says so wherever the advice is shown.
 */
public final class DrugInfo {
    private DrugInfo() {}

    public enum DrugClass {
        PENICILLIN("penicillin", true, "amoxicillin", "amoxycillin", "ampicillin", "cloxacillin", "flucloxacillin",
                "dicloxacillin", "penicillin", "benzathine", "piperacillin", "augmentin", "clavam"),
        CEPHALOSPORIN("cephalosporin", true, "cefalexin", "cephalexin", "cefadroxil", "cefuroxime", "cefixime",
                "cefpodoxime", "ceftriaxone", "cefotaxime", "ceftazidime", "cefoperazone", "cefepime", "cefdinir",
                "cefazolin", "cefaclor", "monocef", "taxim"),
        CARBAPENEM("carbapenem", true, "meropenem", "imipenem", "ertapenem"),
        MACROLIDE("macrolide", true, "azithromycin", "clarithromycin", "erythromycin", "roxithromycin", "azithral", "zithromax"),
        FLUOROQUINOLONE("fluoroquinolone", true, "ciprofloxacin", "levofloxacin", "ofloxacin", "norfloxacin",
                "moxifloxacin", "ciplox"),
        TETRACYCLINE("tetracycline", true, "doxycycline", "tetracycline", "minocycline"),
        SULFONAMIDE("sulfonamide (sulfa)", true, "cotrimoxazole", "co-trimoxazole", "sulfamethoxazole",
                "sulphamethoxazole", "septran", "bactrim"),
        NITROIMIDAZOLE("nitroimidazole", true, "metronidazole", "tinidazole", "ornidazole", "secnidazole", "flagyl", "metrogyl"),
        NITROFURANTOIN("nitrofurantoin", true, "nitrofurantoin"),
        AMINOGLYCOSIDE("aminoglycoside", true, "gentamicin", "amikacin", "tobramycin", "streptomycin"),
        LINCOSAMIDE("lincosamide", true, "clindamycin"),
        GLYCOPEPTIDE("glycopeptide", true, "vancomycin", "teicoplanin"),
        ANTI_TB("anti-tuberculosis", true, "rifampicin", "rifampin", "isoniazid", "pyrazinamide", "ethambutol",
                "rifapentine", "bedaquiline", "akt"),
        ANTIFUNGAL("antifungal", true, "fluconazole", "itraconazole", "voriconazole", "terbinafine", "ketoconazole",
                "clotrimazole", "griseofulvin", "nystatin"),
        ANTIVIRAL("antiviral", true, "acyclovir", "aciclovir", "valacyclovir", "valaciclovir", "oseltamivir",
                "tenofovir", "lamivudine", "dolutegravir", "efavirenz", "zidovudine", "sofosbuvir"),
        ANTIMALARIAL("antimalarial", true, "artemether", "lumefantrine", "artesunate", "chloroquine", "primaquine",
                "mefloquine", "quinine"),
        ANTHELMINTIC("anthelmintic", true, "albendazole", "mebendazole", "ivermectin", "praziquantel"),
        NSAID("NSAID (painkiller)", false, "aspirin", "ibuprofen", "diclofenac", "naproxen", "mefenamic",
                "aceclofenac", "ketorolac", "piroxicam", "indomethacin");

        public final String label;
        public final boolean antimicrobial;
        /** Start of a generic or brand name, matched at the start of a word. */
        final String[] keys;

        DrugClass(String label, boolean antimicrobial, String... keys) {
            this.label = label; this.antimicrobial = antimicrobial; this.keys = keys;
        }

        /** Words people write for an allergy to the whole group ("sulfa allergy"). */
        String[] allergyTerms() {
            switch (this) {
                case PENICILLIN: return new String[]{"penicillin"};
                case CEPHALOSPORIN: return new String[]{"cephalosporin"};
                case MACROLIDE: return new String[]{"macrolide"};
                case FLUOROQUINOLONE: return new String[]{"quinolone", "fluoroquinolone"};
                case TETRACYCLINE: return new String[]{"tetracycline"};
                case SULFONAMIDE: return new String[]{"sulfa", "sulpha", "sulfonamide", "sulphonamide"};
                case AMINOGLYCOSIDE: return new String[]{"aminoglycoside"};
                case NSAID: return new String[]{"nsaid", "painkiller"};
                default: return new String[0];
            }
        }
    }

    private static String norm(String s) {
        return " " + (s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", " ")) + " ";
    }

    /** Drug class of a medicine name, or null when it is not in the table. */
    public static DrugClass classOf(String name) {
        String n = norm(name);
        for (DrugClass c : DrugClass.values())
            for (String k : c.keys) if (n.contains(" " + k)) return c;
        return null;
    }

    public static boolean isAntimicrobial(String name) {
        DrugClass c = classOf(name);
        return c != null && c.antimicrobial;
    }

    public enum Level { NONE, CAUTION, DANGER }

    public static final class AllergyCheck {
        public final Level level;
        public final String message; // English template; the UI translates it
        public final String allergen, drug;

        AllergyCheck(Level level, String message, String allergen, String drug) {
            this.level = level; this.message = message; this.allergen = allergen; this.drug = drug;
        }
    }

    /** Compares a medicine with the allergies written in the profile. */
    public static AllergyCheck checkAllergy(String allergies, String medName) {
        String a = norm(allergies);
        if (a.trim().isEmpty() || medName == null || medName.trim().isEmpty()) return new AllergyCheck(Level.NONE, "", "", "");
        String drug = medName.trim();
        String first = norm(drug).trim().split(" ")[0];
        if (first.length() >= 4 && a.contains(" " + first)) {
            return new AllergyCheck(Level.DANGER, "The profile lists an allergy to %s. Do not use it unless the doctor has checked.", first, drug);
        }
        DrugClass med = classOf(drug);
        if (med == null) return new AllergyCheck(Level.NONE, "", "", drug);
        for (DrugClass allergic : allergyClasses(a)) {
            if (allergic == med)
                return new AllergyCheck(Level.DANGER, "The profile lists an allergy to a %s, and %s is in the same group. Do not use it unless the doctor has checked.",
                        allergic.label, drug);
            if (allergic == DrugClass.PENICILLIN && (med == DrugClass.CEPHALOSPORIN || med == DrugClass.CARBAPENEM))
                return new AllergyCheck(Level.CAUTION, "The profile lists a %s allergy. %s can occasionally cause a reaction in people with this allergy: check with the doctor or pharmacist.",
                        "penicillin", drug);
        }
        return new AllergyCheck(Level.NONE, "", "", drug);
    }

    private static java.util.List<DrugClass> allergyClasses(String normalizedAllergies) {
        java.util.List<DrugClass> out = new java.util.ArrayList<>();
        for (DrugClass c : DrugClass.values()) {
            boolean hit = false;
            for (String k : c.keys) if (normalizedAllergies.contains(" " + k)) hit = true;
            for (String k : c.allergyTerms()) if (normalizedAllergies.contains(" " + k)) hit = true;
            if (hit) out.add(c);
        }
        return out;
    }

    /** Advice on timing with food and other medicines, or "" when none is known. English; the UI translates it. */
    public static String advice(String name) {
        String n = norm(name);
        if (n.contains(" rifamp")) return "Take on an empty stomach, 1 hour before or 2 hours after food. It can turn urine, sweat and tears orange-red; this is harmless.";
        if (n.contains("isoniazid")) return "Take on an empty stomach, 1 hour before or 2 hours after food.";
        if (n.contains("levothyroxine") || n.contains("thyroxine")) return "Take on an empty stomach, 30 to 60 minutes before breakfast, and 4 hours apart from calcium or iron.";
        if (n.contains("alendron")) return "Take first thing in the morning with a full glass of plain water, 30 minutes before food, and stay upright.";
        if (n.contains("prazole")) return "Take 30 to 60 minutes before breakfast.";
        if (n.contains("metformin")) return "Take with or just after a meal.";
        if (n.contains("ferrous") || n.contains(" iron ")) return "Best taken 1 hour before food; not with tea, coffee or milk.";
        if (n.contains("warfarin")) return "Keep the amount of green leafy vegetables steady, avoid alcohol, and tell every doctor that you take it.";
        if (n.contains("nitrofurantoin")) return "Take with food or milk.";
        DrugClass c = classOf(name);
        if (c == null) return "";
        switch (c) {
            case FLUOROQUINOLONE: return "Take at least 2 hours before, or 6 hours after, antacids, iron, calcium, zinc, or milk and curd on their own.";
            case TETRACYCLINE: return "Take with a full glass of water and stay upright for 30 minutes. Keep 2 to 3 hours apart from milk, antacids, iron and calcium.";
            case NITROIMIDAZOLE: return "Do not drink alcohol during the course and for 3 days after it.";
            case SULFONAMIDE: return "Drink plenty of water while taking it.";
            case NSAID: return "Take after food with a full glass of water.";
            default: return "";
        }
    }
}
