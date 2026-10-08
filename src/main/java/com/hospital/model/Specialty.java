package com.hospital.model;

public enum Specialty {
    EMERGENCY_MEDICINE("Emergency Medicine"),
    CRITICAL_CARE("Critical Care"),
    TRAUMA_SURGERY("Trauma Surgery"),
    CARDIOLOGY("Cardiology"),
    PEDIATRICS("Pediatrics"),
    INTERNAL_MEDICINE("Internal Medicine");

    private final String displayName;

    Specialty(String displayName) { this.displayName = displayName; }

    public String getDisplayName() { return displayName; }

    @Override
    public String toString() { return displayName; }
}
