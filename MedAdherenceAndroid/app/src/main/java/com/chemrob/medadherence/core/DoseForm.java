package com.chemrob.medadherence.core;

/**
 * What kind of medicine it is. The form decides the unit, whether the dose can be watched on
 * camera, and the step-by-step "how to use" shown with the medicine. Skin products are used as
 * directed: reminders are optional and they are not counted in adherence unless given times.
 */
public enum DoseForm {
    TABLET("tablet", "Tablet / capsule", "tablet(s)", true, new String[]{
            "Swallow it whole with a full glass of water, unless told to chew or dissolve it.",
            "Do not crush or break it unless the label or your pharmacist says you can."}),
    LIQUID("liquid", "Syrup / liquid", "ml", true, new String[]{
            "Shake the bottle well.",
            "Measure with the cup or oral syringe that came with it, not a kitchen spoon.",
            "Read the measure at eye level.",
            "Rinse the measure with clean water after use."}),
    INJECTION("injection", "Injection", "unit(s)", false, new String[]{
            "Wash your hands with soap and water.",
            "Check the name, the dose and the expiry date; the liquid should look as described on the label.",
            "Clean the skin with an alcohol swab and let it dry.",
            "Inject at a different spot from last time.",
            "Put the used needle straight into a sharps box; never reuse or share it."}),
    INHALER("inhaler", "Inhaler (pump)", "puff(s)", false, new String[]{
            "Remove the cap and shake the inhaler.",
            "Breathe out fully, away from the inhaler.",
            "Seal your lips around the mouthpiece.",
            "Press once as you start to breathe in slowly and deeply.",
            "Hold your breath for about 10 seconds, then breathe out slowly.",
            "Wait 30 to 60 seconds before a second puff.",
            "After a steroid inhaler, rinse your mouth with water and spit it out."}),
    EYE("eye", "Eye drops", "drop(s)", false, new String[]{
            "Wash your hands.",
            "Tilt your head back and gently pull the lower eyelid down.",
            "Let one drop fall into the pocket; do not touch the eye with the tip.",
            "Close the eye for 1 to 2 minutes and press gently on the inner corner.",
            "Wait 5 minutes before using a different eye drop.",
            "Write the opening date on the bottle; many drops must be thrown away 4 weeks after opening (check the label)."}),
    EAR("ear", "Ear drops", "drop(s)", false, new String[]{
            "Warm the bottle in your hands for a few minutes.",
            "Lie on your side with the affected ear facing up.",
            "Gently pull the ear up and back, then put the drops in without touching the ear with the tip.",
            "Stay lying down for about 5 minutes."}),
    SKIN("skin", "Skin: lotion / cream / oil", "", false, new String[]{
            "Wash and dry your hands and the area to be treated.",
            "Apply a thin layer only to the affected skin and rub it in gently.",
            "Wash your hands afterwards, unless your hands are being treated.",
            "Keep it away from the eyes, mouth and broken skin unless told otherwise.",
            "Do not cover the area with a bandage unless your doctor told you to."});

    public final String code, label, unit;
    /** Can be taken in front of the camera (swallowed medicines only). */
    public final boolean observable;
    public final String[] howTo;

    DoseForm(String code, String label, String unit, boolean observable, String[] howTo) {
        this.code = code; this.label = label; this.unit = unit; this.observable = observable; this.howTo = howTo;
    }

    public static DoseForm of(String code) {
        for (DoseForm f : values()) if (f.code.equals(code)) return f;
        return TABLET;
    }

    /** Eye and ear drops ask which side. */
    public boolean hasSide() { return this == EYE || this == EAR; }

    /** Everything except skin products can be counted down from the stock. */
    public boolean countsStock() { return this != SKIN; }
}
