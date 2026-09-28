package com.chemrob.medadherence.core;

/** What happened to one scheduled dose. */
public enum DoseStatus {
    PENDING,   // not yet due, or inside the grace window with no action
    TAKEN,
    SKIPPED,   // patient explicitly declined / held the dose
    MISSED,    // grace window passed with no action (derived, never stored)
    SNOOZED
}
