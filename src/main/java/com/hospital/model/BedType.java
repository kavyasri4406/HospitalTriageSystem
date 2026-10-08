package com.hospital.model;

/** Kinds of beds in the hospital, ordered roughly from highest to lowest acuity. */
public enum BedType {
    ICU("Intensive Care Unit", "ICU"),
    TRAUMA("Trauma Bay", "TRM"),
    MONITORED("Monitored / Step-Down", "MON"),
    PEDIATRIC("Pediatric Ward", "PED"),
    GENERAL("General Emergency", "GEN");

    private final String displayName;
    private final String prefix;

    BedType(String displayName, String prefix) {
        this.displayName = displayName;
        this.prefix = prefix;
    }

    public String getDisplayName() { return displayName; }
    public String getPrefix() { return prefix; }

    @Override
    public String toString() { return displayName; }
}
