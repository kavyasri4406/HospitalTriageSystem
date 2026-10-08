package com.hospital.persistence.dao;

import com.hospital.model.Doctor;
import com.hospital.model.Specialty;
import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DoctorDAO implements Dao<Doctor, Integer> {

    private static final String COLUMNS = "id, name, specialty, max_patients, active_patients, on_duty";

    private final DatabaseManager db;

    public DoctorDAO(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public List<Doctor> findAll() {
        return db.run(conn -> {
            List<Doctor> doctors = new ArrayList<>();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + COLUMNS + " FROM doctors ORDER BY id")) {
                while (rs.next()) doctors.add(map(rs));
            }
            return doctors;
        });
    }

    @Override
    public Optional<Doctor> findById(Integer id) {
        return db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM doctors WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void insert(Doctor d) {
        int id = db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO doctors (name, specialty, max_patients, active_patients, on_duty) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, d.getName());
                ps.setString(2, d.getSpecialty().name());
                ps.setInt(3, d.getMaxPatients());
                ps.setInt(4, d.getActivePatients());
                ps.setInt(5, d.isOnDuty() ? 1 : 0);
                return db.executeInsert(ps);
            }
        });
        d.setId(id);
    }

    @Override
    public void update(Doctor d) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE doctors SET active_patients = ?, on_duty = ? WHERE id = ?")) {
                ps.setInt(1, d.getActivePatients());
                ps.setInt(2, d.isOnDuty() ? 1 : 0);
                ps.setInt(3, d.getId());
                return ps.executeUpdate();
            }
        });
    }

    private Doctor map(ResultSet rs) throws SQLException {
        return new Doctor(
                rs.getInt("id"),
                rs.getString("name"),
                Specialty.valueOf(rs.getString("specialty")),
                rs.getInt("max_patients"),
                rs.getInt("active_patients"),
                rs.getInt("on_duty") == 1);
    }
}
