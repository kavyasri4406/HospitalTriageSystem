package com.hospital.persistence.dao;

import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.hospital.persistence.JdbcUtils.fromDb;
import static com.hospital.persistence.JdbcUtils.getNullableInt;
import static com.hospital.persistence.JdbcUtils.setNullableInt;
import static com.hospital.persistence.JdbcUtils.toDb;

public class BedDAO implements Dao<Bed, String> {

    private final DatabaseManager db;

    public BedDAO(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public List<Bed> findAll() {
        return db.run(conn -> {
            List<Bed> beds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT bed_id, ward_name, bed_type, status, patient_id, updated_at FROM beds ORDER BY bed_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) beds.add(map(rs));
            }
            return beds;
        });
    }

    @Override
    public Optional<Bed> findById(String bedId) {
        return db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT bed_id, ward_name, bed_type, status, patient_id, updated_at FROM beds WHERE bed_id = ?")) {
                ps.setString(1, bedId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void insert(Bed bed) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO beds (bed_id, ward_name, bed_type, status, patient_id, updated_at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, bed.getBedId());
                ps.setString(2, bed.getWardName());
                ps.setString(3, bed.getType().name());
                ps.setString(4, bed.getStatus().name());
                setNullableInt(ps, 5, bed.getPatientId());
                ps.setString(6, toDb(bed.getUpdatedAt()));
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void update(Bed bed) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE beds SET status = ?, patient_id = ?, updated_at = ? WHERE bed_id = ?")) {
                ps.setString(1, bed.getStatus().name());
                setNullableInt(ps, 2, bed.getPatientId());
                ps.setString(3, toDb(bed.getUpdatedAt()));
                ps.setString(4, bed.getBedId());
                return ps.executeUpdate();
            }
        });
    }

    private Bed map(ResultSet rs) throws SQLException {
        return new Bed(
                rs.getString("bed_id"),
                rs.getString("ward_name"),
                BedType.valueOf(rs.getString("bed_type")),
                BedStatus.valueOf(rs.getString("status")),
                getNullableInt(rs, "patient_id"),
                fromDb(rs.getString("updated_at")));
    }
}
