package com.hospital.persistence.dao;

import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** Key/value settings store. Upsert is done as DELETE + INSERT so it works on both MySQL and SQLite. */
public class SettingsDAO {

    private final DatabaseManager db;

    public SettingsDAO(DatabaseManager db) {
        this.db = db;
    }

    public Map<String, String> loadAll() {
        return db.run(conn -> {
            Map<String, String> values = new HashMap<>();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT setting_key, setting_value FROM app_settings")) {
                while (rs.next()) values.put(rs.getString(1), rs.getString(2));
            }
            return values;
        });
    }

    public void saveAll(Map<String, String> values) {
        db.inTransaction(conn -> {
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM app_settings WHERE setting_key = ?");
                 PreparedStatement insert = conn.prepareStatement(
                         "INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)")) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    delete.setString(1, e.getKey());
                    delete.executeUpdate();
                    insert.setString(1, e.getKey());
                    insert.setString(2, e.getValue());
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }
}
