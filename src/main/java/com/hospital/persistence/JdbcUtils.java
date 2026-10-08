package com.hospital.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;

/**
 * Small JDBC helpers. Timestamps are stored as ISO-8601 text so the same schema
 * behaves identically on MySQL and SQLite.
 */
public final class JdbcUtils {

    private JdbcUtils() {}

    public static String toDb(LocalDateTime time) {
        return time == null ? null : time.withNano(0).toString();
    }

    public static LocalDateTime fromDb(String text) {
        return text == null || text.isBlank() ? null : LocalDateTime.parse(text);
    }

    public static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    public static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
