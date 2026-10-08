package com.hospital.persistence.dao;

import com.hospital.model.Alert;
import com.hospital.model.AlertSeverity;
import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.hospital.persistence.JdbcUtils.fromDb;
import static com.hospital.persistence.JdbcUtils.getNullableInt;
import static com.hospital.persistence.JdbcUtils.setNullableInt;
import static com.hospital.persistence.JdbcUtils.toDb;

public class AlertDAO implements Dao<Alert, Integer> {

    private static final String COLUMNS = "id, severity, category, message, patient_id, created_at, acknowledged";

    private final DatabaseManager db;

    public AlertDAO(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public List<Alert> findAll() {
        return findRecent(Integer.MAX_VALUE);
    }

    public List<Alert> findRecent(int limit) {
        return db.run(conn -> {
            List<Alert> alerts = new ArrayList<>();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + COLUMNS + " FROM alerts ORDER BY id DESC LIMIT " + limit)) {
                while (rs.next()) alerts.add(map(rs));
            }
            return alerts;
        });
    }

    @Override
    public Optional<Alert> findById(Integer id) {
        return db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM alerts WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void insert(Alert a) {
        int id = db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO alerts (severity, category, message, patient_id, created_at, acknowledged) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, a.getSeverity().name());
                ps.setString(2, a.getCategory().name());
                ps.setString(3, a.getMessage());
                setNullableInt(ps, 4, a.getPatientId());
                ps.setString(5, toDb(a.getCreatedAt()));
                ps.setInt(6, a.isAcknowledged() ? 1 : 0);
                return db.executeInsert(ps);
            }
        });
        a.setId(id);
    }

    @Override
    public void update(Alert a) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE alerts SET acknowledged = ? WHERE id = ?")) {
                ps.setInt(1, a.isAcknowledged() ? 1 : 0);
                ps.setInt(2, a.getId());
                return ps.executeUpdate();
            }
        });
    }

    public void acknowledgeAll() {
        db.run(conn -> {
            try (Statement s = conn.createStatement()) {
                return s.executeUpdate("UPDATE alerts SET acknowledged = 1 WHERE acknowledged = 0");
            }
        });
    }

    private Alert map(ResultSet rs) throws SQLException {
        return new Alert(
                rs.getInt("id"),
                AlertSeverity.valueOf(rs.getString("severity")),
                Alert.Category.valueOf(rs.getString("category")),
                rs.getString("message"),
                getNullableInt(rs, "patient_id"),
                fromDb(rs.getString("created_at")),
                rs.getInt("acknowledged") == 1);
    }
}
