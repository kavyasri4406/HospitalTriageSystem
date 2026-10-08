package com.hospital.persistence.dao;

import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;
import com.hospital.model.VitalsRecord;
import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.hospital.persistence.JdbcUtils.fromDb;
import static com.hospital.persistence.JdbcUtils.toDb;

/** Append-only history of every set of vitals recorded for a patient. */
public class VitalsHistoryDAO {

    private final DatabaseManager db;

    public VitalsHistoryDAO(DatabaseManager db) {
        this.db = db;
    }

    public void insert(int patientId, Vitals v, TriageLevel level, double severity, String recordedBy, LocalDateTime when) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO vitals_history (patient_id, heart_rate, systolic_bp, diastolic_bp, respiratory_rate, "
                            + "spo2, temperature, pain_score, gcs, esi_level, severity_score, recorded_by, recorded_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, patientId);
                ps.setInt(2, v.heartRate());
                ps.setInt(3, v.systolicBp());
                ps.setInt(4, v.diastolicBp());
                ps.setInt(5, v.respiratoryRate());
                ps.setInt(6, v.spo2());
                ps.setDouble(7, v.temperature());
                ps.setInt(8, v.painScore());
                ps.setInt(9, v.gcs());
                ps.setInt(10, level.getEsi());
                ps.setDouble(11, severity);
                ps.setString(12, recordedBy);
                ps.setString(13, toDb(when));
                return db.executeInsert(ps);
            }
        });
    }

    /** Oldest first. */
    public List<VitalsRecord> findByPatient(int patientId) {
        return db.run(conn -> {
            List<VitalsRecord> records = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, patient_id, heart_rate, systolic_bp, diastolic_bp, respiratory_rate, spo2, temperature, "
                            + "pain_score, gcs, esi_level, severity_score, recorded_by, recorded_at "
                            + "FROM vitals_history WHERE patient_id = ? ORDER BY id")) {
                ps.setInt(1, patientId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Vitals v = new Vitals(rs.getInt("heart_rate"), rs.getInt("systolic_bp"), rs.getInt("diastolic_bp"),
                                rs.getInt("respiratory_rate"), rs.getInt("spo2"), rs.getDouble("temperature"),
                                rs.getInt("pain_score"), rs.getInt("gcs"));
                        records.add(new VitalsRecord(rs.getInt("id"), rs.getInt("patient_id"), v,
                                TriageLevel.fromEsi(rs.getInt("esi_level")), rs.getDouble("severity_score"),
                                rs.getString("recorded_by"), fromDb(rs.getString("recorded_at"))));
                    }
                }
            }
            return records;
        });
    }
}
