package com.chemrob.medadherence.core;

public final class AdherenceStats {
    public String label = "";
    public int due;          // settled doses (taken / skipped / missed)
    public int taken;        // on time + late
    public int onTime;
    public int late;
    public int skipped;
    public int missed;
    public int pending;      // due but still inside the grace window (not in due)
    public int observedDue;
    public int observedVerified;
    public int daysCovered;  // days on which every due dose was taken
    public int daysElapsed;  // days with at least one settled dose

    public AdherenceStats(String label) { this.label = label; }

    public double takingPercent() { return due == 0 ? 100 : 100.0 * taken / due; }
    public double timingPercent() { return due == 0 ? 100 : 100.0 * onTime / due; }
    public double daysCoveredPercent() { return daysElapsed == 0 ? 100 : 100.0 * daysCovered / daysElapsed; }
    public double verifiedPercent() { return observedDue == 0 ? 100 : 100.0 * observedVerified / observedDue; }

    /** Conventional 80 % threshold used in adherence research. */
    public String category() {
        if (due == 0) return "No doses due yet";
        double p = takingPercent();
        return p >= 80 ? "Adherent" : p >= 50 ? "Partially adherent" : "Non-adherent";
    }
}
