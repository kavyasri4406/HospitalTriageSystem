package com.hospital.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Configurable system behaviour, persisted in the {@code app_settings} table.
 *
 * @param autoAdmit               automatically allocate free beds to waiting patients on every refresh
 * @param highOccupancyThreshold  occupancy fraction (0.50-1.00) that raises the "divert ambulances" alert
 * @param darkMode                dark colour theme for the UI
 * @param refreshSeconds          UI auto-refresh / aging interval, 2-60 seconds
 * @param cleaningMinutes         expected bed turnaround after discharge, used by the wait-time predictor
 */
public record AppSettings(boolean autoAdmit, double highOccupancyThreshold, boolean darkMode,
                          int refreshSeconds, int cleaningMinutes) {

    public static AppSettings defaults() {
        return new AppSettings(false, 0.90, false, 5, 15);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (Double.isNaN(highOccupancyThreshold) || highOccupancyThreshold < 0.5 || highOccupancyThreshold > 1.0) {
            errors.add("Occupancy alert threshold must be between 50% and 100%.");
        }
        if (refreshSeconds < 2 || refreshSeconds > 60) {
            errors.add("Refresh interval must be between 2 and 60 seconds.");
        }
        if (cleaningMinutes < 0 || cleaningMinutes > 240) {
            errors.add("Bed cleaning time must be between 0 and 240 minutes.");
        }
        return errors;
    }
}
