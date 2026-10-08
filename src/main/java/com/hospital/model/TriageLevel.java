package com.hospital.model;

/**
 * Emergency Severity Index (ESI) levels, 1 = most urgent, 5 = least urgent.
 * Each level carries the maximum recommended wait time before the patient
 * must be seen, which drives the wait-aging and overdue alerts.
 */
public enum TriageLevel {
    RESUSCITATION(1, "Resuscitation", 0, "#d32f2f"),
    EMERGENT(2, "Emergent", 10, "#f57c00"),
    URGENT(3, "Urgent", 30, "#fbc02d"),
    LESS_URGENT(4, "Less Urgent", 60, "#388e3c"),
    NON_URGENT(5, "Non-Urgent", 120, "#1976d2");

    private final int esi;
    private final String label;
    private final int targetWaitMinutes;
    private final String colorHex;

    TriageLevel(int esi, String label, int targetWaitMinutes, String colorHex) {
        this.esi = esi;
        this.label = label;
        this.targetWaitMinutes = targetWaitMinutes;
        this.colorHex = colorHex;
    }

    public int getEsi() { return esi; }
    public String getLabel() { return label; }
    public int getTargetWaitMinutes() { return targetWaitMinutes; }
    public String getColorHex() { return colorHex; }

    public boolean isCritical() { return esi <= 2; }

    public static TriageLevel fromEsi(int esi) {
        for (TriageLevel level : values()) {
            if (level.esi == esi) return level;
        }
        throw new IllegalArgumentException("Invalid ESI level: " + esi);
    }

    @Override
    public String toString() {
        return "ESI-" + esi + " " + label;
    }
}
