package com.hospital.persistence.dao;

import com.hospital.model.Role;
import com.hospital.model.StaffUser;
import com.hospital.persistence.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.hospital.persistence.JdbcUtils.fromDb;
import static com.hospital.persistence.JdbcUtils.toDb;

public class StaffDAO implements Dao<StaffUser, Integer> {

    private static final String COLUMNS = "id, username, full_name, role, password_hash, active, created_at, last_login";

    private final DatabaseManager db;

    public StaffDAO(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public List<StaffUser> findAll() {
        return db.run(conn -> {
            List<StaffUser> users = new ArrayList<>();
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT " + COLUMNS + " FROM staff_users ORDER BY id")) {
                while (rs.next()) users.add(map(rs));
            }
            return users;
        });
    }

    @Override
    public Optional<StaffUser> findById(Integer id) {
        return findOne("id = ?", ps -> ps.setInt(1, id));
    }

    /** Case-insensitive lookup by username. */
    public Optional<StaffUser> findByUsername(String username) {
        return findOne("LOWER(username) = ?", ps -> ps.setString(1, username.toLowerCase()));
    }

    @Override
    public void insert(StaffUser u) {
        int id = db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO staff_users (username, full_name, role, password_hash, active, created_at, last_login) "
                            + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, u.getUsername());
                ps.setString(2, u.getFullName());
                ps.setString(3, u.getRole().name());
                ps.setString(4, u.getPasswordHash());
                ps.setInt(5, u.isActive() ? 1 : 0);
                ps.setString(6, toDb(u.getCreatedAt()));
                ps.setString(7, toDb(u.getLastLogin()));
                return db.executeInsert(ps);
            }
        });
        u.setId(id);
    }

    @Override
    public void update(StaffUser u) {
        db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE staff_users SET full_name = ?, role = ?, password_hash = ?, active = ?, last_login = ? WHERE id = ?")) {
                ps.setString(1, u.getFullName());
                ps.setString(2, u.getRole().name());
                ps.setString(3, u.getPasswordHash());
                ps.setInt(4, u.isActive() ? 1 : 0);
                ps.setString(5, toDb(u.getLastLogin()));
                ps.setInt(6, u.getId());
                return ps.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<StaffUser> findOne(String where, Binder binder) {
        return db.run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM staff_users WHERE " + where)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    private StaffUser map(ResultSet rs) throws SQLException {
        return new StaffUser(
                rs.getInt("id"),
                rs.getString("username"),
                rs.getString("full_name"),
                Role.valueOf(rs.getString("role")),
                rs.getString("password_hash"),
                rs.getInt("active") == 1,
                fromDb(rs.getString("created_at")),
                fromDb(rs.getString("last_login")));
    }
}
