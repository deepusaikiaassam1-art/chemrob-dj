package com.chemrob.medadherence.core;

/** Outcome of a camera-observed dose. */
public enum Verification {
    NOT_REQUIRED,
    AUTO_VERIFIED,       // on-device checks passed
    NEEDS_REVIEW,        // evidence captured, checks inconclusive
    PHARMACIST_APPROVED,
    PHARMACIST_REJECTED
}
