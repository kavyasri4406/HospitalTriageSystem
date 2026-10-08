package com.hospital.model;

public enum AlertSeverity {
    CRITICAL("#d32f2f"),
    WARNING("#f57c00"),
    INFO("#1976d2");

    private final String colorHex;

    AlertSeverity(String colorHex) { this.colorHex = colorHex; }

    public String getColorHex() { return colorHex; }
}
