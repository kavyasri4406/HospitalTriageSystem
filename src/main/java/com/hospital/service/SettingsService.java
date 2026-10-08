package com.hospital.service;

import com.hospital.model.AppSettings;
import com.hospital.persistence.dao.SettingsDAO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads, validates and saves {@link AppSettings}. Unknown or malformed stored values fall back to defaults. */
public class SettingsService {

    private final SettingsDAO dao;
    private AppSettings current = AppSettings.defaults();

    public SettingsService(SettingsDAO dao) {
        this.dao = dao;
    }

    public void load() {
        Map<String, String> v = dao.loadAll();
        AppSettings d = AppSettings.defaults();
        AppSettings loaded = new AppSettings(
                bool(v.get("auto_admit"), d.autoAdmit()),
                dbl(v.get("high_occupancy_threshold"), d.highOccupancyThreshold()),
                bool(v.get("dark_mode"), d.darkMode()),
                integer(v.get("refresh_seconds"), d.refreshSeconds()),
                integer(v.get("cleaning_minutes"), d.cleaningMinutes()));
        current = loaded.validate().isEmpty() ? loaded : d;
    }

    public AppSettings get() {
        return current;
    }

    public OperationResult update(AppSettings settings) {
        List<String> errors = settings.validate();
        if (!errors.isEmpty()) return OperationResult.invalid(errors);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("auto_admit", String.valueOf(settings.autoAdmit()));
        values.put("high_occupancy_threshold", String.valueOf(settings.highOccupancyThreshold()));
        values.put("dark_mode", String.valueOf(settings.darkMode()));
        values.put("refresh_seconds", String.valueOf(settings.refreshSeconds()));
        values.put("cleaning_minutes", String.valueOf(settings.cleaningMinutes()));
        dao.saveAll(values);
        current = settings;
        return OperationResult.ok("Settings saved.");
    }

    private static boolean bool(String s, boolean fallback) {
        return s == null ? fallback : Boolean.parseBoolean(s);
    }

    private static double dbl(String s, double fallback) {
        try { return s == null ? fallback : Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static int integer(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }
}
