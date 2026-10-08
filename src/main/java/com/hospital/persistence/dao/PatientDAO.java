package com.hospital.persistence.dao;

import com.hospital.model.Patient;
import com.hospital.model.PatientFactory;
import com.hospital.model.PatientStatus;
import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;
import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.hospital.persistence.JdbcUtils.fromDb;
import static com.hospital.persistence.JdbcUtils.getNullableInt;
import static com.hospital.persistence.JdbcUtils.setNullableInt;
import static com.hospital.persistence.JdbcUtils.toDb;

public class PatientDAO implements Dao<Patient, Integer> {

    private static final String COLUMNS =
            "id, mrn, full_name, age, gender, category, chief_complaint, trauma, heart_rate, systolic_bp, "
                    + "diastolic_bp, respiratory_rate, spo2, temperature, pain_score, gcs, expected_resources, "
                    + "esi_level, severity_score, priority_score, status, bed_id, doctor_id, intake_time, "
                    + "admitted_time, discharged_time";

    private final DatabaseManager db;

    public PatientDAO(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public List<Patient> findAll() {
        return query("SELECT " + COLUMNS + " FROM patients ORDER BY id");
    }

    @Override
    public Optional<Patient> findById(Integer id) {
        return db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM patients WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Patients still in the department (waiting or admitted), used to rebuild in-memory state on startup. */
    public List<Patient> findActive() {
        return query("SELECT " + COLUMNS + " FROM patients WHERE status IN ('WAITING', 'ADMITTED') ORDER BY id");
    }

    public List<Patient> findRecentlyClosed(int limit) {
        return query("SELECT " + COLUMNS + " FROM patients WHERE status IN ('DISCHARGED', 'LEFT_WITHOUT_BEING_SEEN') "
                + "ORDER BY discharged_time DESC LIMIT " + Math.max(1, limit));
    }

    public Map<PatientStatus, Integer> countByStatus() {
        return db.run(conn -> {
            Map<PatientStatus, Integer> counts = new EnumMap<>(PatientStatus.class);
            for (PatientStatus s : PatientStatus.values()) counts.put(s, 0);
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT status, COUNT(*) FROM patients GROUP BY status")) {
                while (rs.next()) {
                    counts.put(PatientStatus.valueOf(rs.getString(1)), rs.getInt(2));
                }
            }
            return counts;
        });
    }

    @Override
    public void insert(Patient p) {
        String sql = "INSERT INTO patients (mrn, full_name, age, gender, category, chief_complaint, trauma, heart_rate, "
                + "systolic_bp, diastolic_bp, respiratory_rate, spo2, temperature, pain_score, gcs, expected_resources, "
                + "esi_level, severity_score, priority_score, status, bed_id, doctor_id, intake_time, admitted_time, "
                + "discharged_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        int id = db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                Vitals v = p.getVitals();
                ps.setString(1, p.getMrn());
                ps.setString(2, p.getFullName());
                ps.setInt(3, p.getAge());
                ps.setString(4, p.getGender());
                ps.setString(5, p.getCategory());
                ps.setString(6, p.getChiefComplaint());
                ps.setInt(7, p.isTrauma() ? 1 : 0);
                ps.setInt(8, v.heartRate());
                ps.setInt(9, v.systolicBp());
                ps.setInt(10, v.diastolicBp());
                ps.setInt(11, v.respiratoryRate());
                ps.setInt(12, v.spo2());
                ps.setDouble(13, v.temperature());
                ps.setInt(14, v.painScore());
                ps.setInt(15, v.gcs());
                ps.setInt(16, p.getExpectedResources());
                ps.setInt(17, p.getTriageLevel().getEsi());
                ps.setDouble(18, p.getSeverityScore());
                ps.setDouble(19, p.getPriorityScore());
                ps.setString(20, p.getStatus().name());
                ps.setString(21, p.getAssignedBedId());
                setNullableInt(ps, 22, p.getAssignedDoctorId());
                ps.setString(23, toDb(p.getIntakeTime()));
                ps.setString(24, toDb(p.getAdmittedTime()));
                ps.setString(25, toDb(p.getDischargedTime()));
                return db.executeInsert(ps);
            }
        });
        p.setId(id);
    }

    /** Updates the mutable columns: latest vitals, triage result and disposition. */
    @Override
    public void update(Patient p) {
        String sql = "UPDATE patients SET heart_rate = ?, systolic_bp = ?, diastolic_bp = ?, respiratory_rate = ?, "
                + "spo2 = ?, temperature = ?, pain_score = ?, gcs = ?, expected_resources = ?, "
                + "esi_level = ?, severity_score = ?, priority_score = ?, status = ?, "
                + "bed_id = ?, doctor_id = ?, admitted_time = ?, discharged_time = ? WHERE id = ?";
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                Vitals v = p.getVitals();
                ps.setInt(1, v.heartRate());
                ps.setInt(2, v.systolicBp());
                ps.setInt(3, v.diastolicBp());
                ps.setInt(4, v.respiratoryRate());
                ps.setInt(5, v.spo2());
                ps.setDouble(6, v.temperature());
                ps.setInt(7, v.painScore());
                ps.setInt(8, v.gcs());
                ps.setInt(9, p.getExpectedResources());
                ps.setInt(10, p.getTriageLevel().getEsi());
                ps.setDouble(11, p.getSeverityScore());
                ps.setDouble(12, p.getPriorityScore());
                ps.setString(13, p.getStatus().name());
                ps.setString(14, p.getAssignedBedId());
                setNullableInt(ps, 15, p.getAssignedDoctorId());
                ps.setString(16, toDb(p.getAdmittedTime()));
                ps.setString(17, toDb(p.getDischargedTime()));
                ps.setInt(18, p.getId());
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Case-insensitive search by name or MRN across every patient ever registered, newest first.
     * An empty query returns the most recent patients.
     */
    public List<Patient> search(String text, int limit) {
        String q = text == null ? "" : text.trim().toLowerCase();
        // '!' as LIKE escape character: '\' would be an escape inside MySQL string literals
        String like = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return db.run(conn -> {
            List<Patient> result = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM patients "
                    + "WHERE LOWER(full_name) LIKE ? ESCAPE '!' OR LOWER(mrn) LIKE ? ESCAPE '!' "
                    + "ORDER BY id DESC LIMIT " + Math.max(1, limit))) {
                ps.setString(1, like);
                ps.setString(2, like);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(map(rs));
                }
            }
            return result;
        });
    }

    private List<Patient> query(String sql) {
        return db.run(conn -> {
            List<Patient> result = new ArrayList<>();
            try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) result.add(map(rs));
            }
            return result;
        });
    }

    private Patient map(ResultSet rs) throws SQLException {
        Vitals vitals = new Vitals(
                rs.getInt("heart_rate"), rs.getInt("systolic_bp"), rs.getInt("diastolic_bp"),
                rs.getInt("respiratory_rate"), rs.getInt("spo2"), rs.getDouble("temperature"),
                rs.getInt("pain_score"), rs.getInt("gcs"));
        Patient p = PatientFactory.create(
                rs.getString("full_name"), rs.getInt("age"), rs.getString("gender"),
                rs.getString("chief_complaint"), rs.getInt("trauma") == 1, vitals,
                rs.getInt("expected_resources"), fromDb(rs.getString("intake_time")));
        p.setId(rs.getInt("id"));
        p.setMrn(rs.getString("mrn"));
        double severity = rs.getDouble("severity_score");
        double priority = rs.getDouble("priority_score");
        p.applyTriage(TriageLevel.fromEsi(rs.getInt("esi_level")), severity, priority - severity, priority);
        p.restoreDisposition(
                PatientStatus.valueOf(rs.getString("status")),
                rs.getString("bed_id"),
                getNullableInt(rs, "doctor_id"),
                fromDb(rs.getString("admitted_time")),
                fromDb(rs.getString("discharged_time")));
        return p;
    }
}
