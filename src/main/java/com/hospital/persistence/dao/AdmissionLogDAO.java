package com.hospital.persistence.dao;

import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.hospital.persistence.JdbcUtils.fromDb;
import static com.hospital.persistence.JdbcUtils.setNullableInt;
import static com.hospital.persistence.JdbcUtils.toDb;

/** Append-only audit trail of every triage, admission, transfer and discharge event. */
public class AdmissionLogDAO {

    public enum Action { REGISTERED, REASSESSED, ADMITTED, TRANSFERRED, DISCHARGED, LEFT_WITHOUT_BEING_SEEN, BED_STATUS }

    /**
     * @param performedBy username of the staff member (or "system") who caused the event; null for old rows
     */
    public record LogEntry(int id, int patientId, String bedId, Integer doctorId, String action,
                           String details, Double priorityScore, String performedBy, LocalDateTime createdAt) {}

    private static final String COLUMNS =
            "id, patient_id, bed_id, doctor_id, action, details, priority_score, performed_by, created_at";

    private final DatabaseManager db;

    public AdmissionLogDAO(DatabaseManager db) {
        this.db = db;
    }

    public void log(int patientId, String bedId, Integer doctorId, Action action, String details,
                    Double priorityScore, String performedBy, LocalDateTime when) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO admission_logs (patient_id, bed_id, doctor_id, action, details, priority_score, "
                            + "performed_by, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setInt(1, patientId);
                ps.setString(2, bedId);
                setNullableInt(ps, 3, doctorId);
                ps.setString(4, action.name());
                ps.setString(5, details == null ? null : details.substring(0, Math.min(500, details.length())));
                if (priorityScore == null) ps.setNull(6, java.sql.Types.DOUBLE);
                else ps.setDouble(6, priorityScore);
                ps.setString(7, performedBy);
                ps.setString(8, toDb(when));
                return ps.executeUpdate();
            }
        });
    }

    /** Newest first. */
    public List<LogEntry> recent(int limit) {
        return query("SELECT " + COLUMNS + " FROM admission_logs ORDER BY id DESC LIMIT " + Math.max(1, limit), null);
    }

    /** Full timeline of one patient, oldest first. */
    public List<LogEntry> findByPatient(int patientId) {
        return query("SELECT " + COLUMNS + " FROM admission_logs WHERE patient_id = ? ORDER BY id", patientId);
    }

    private List<LogEntry> query(String sql, Integer patientId) {
        return db.run(conn -> {
            List<LogEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (patientId != null) ps.setInt(1, patientId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) entries.add(map(rs));
                }
            }
            return entries;
        });
    }

    private LogEntry map(ResultSet rs) throws SQLException {
        int doctor = rs.getInt("doctor_id");
        Integer doctorId = rs.wasNull() ? null : doctor;
        double score = rs.getDouble("priority_score");
        Double priority = rs.wasNull() ? null : score;
        return new LogEntry(rs.getInt("id"), rs.getInt("patient_id"), rs.getString("bed_id"),
                doctorId, rs.getString("action"), rs.getString("details"), priority,
                rs.getString("performed_by"), fromDb(rs.getString("created_at")));
    }
}
