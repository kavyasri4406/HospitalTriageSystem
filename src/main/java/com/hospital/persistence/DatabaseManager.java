package com.hospital.persistence;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single JDBC {@link Connection} used by the desktop app and provides
 * transaction demarcation for the DAOs. Supports MySQL and SQLite.
 */
public class DatabaseManager implements AutoCloseable {

    public enum Dialect { MYSQL, SQLITE }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private final Dialect dialect;
    private final String description;
    private int transactionDepth;

    private DatabaseManager(Connection connection, Dialect dialect, String description) {
        this.connection = connection;
        this.dialect = dialect;
        this.description = description;
    }

    public static DatabaseManager connect(DatabaseConfig config) {
        if (config.mode() != DatabaseConfig.Mode.SQLITE) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                Connection c = DriverManager.getConnection(config.mysqlUrl(), config.user(), config.password());
                System.out.println("[JDBC] Connected to MySQL: " + c.getCatalog());
                return new DatabaseManager(c, Dialect.MYSQL, "MySQL (" + c.getCatalog() + ")");
            } catch (ClassNotFoundException | SQLException e) {
                if (config.mode() == DatabaseConfig.Mode.MYSQL) {
                    throw new DataAccessException("Could not connect to MySQL: " + e.getMessage(), e);
                }
                System.out.println("[JDBC] MySQL unavailable (" + firstLine(e.getMessage()) + "), using SQLite.");
            }
        }
        try {
            Class.forName("org.sqlite.JDBC");
            File file = new File(config.sqliteFile());
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new DataAccessException("Cannot create folder " + parent);
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getPath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            System.out.println("[JDBC] Connected to SQLite: " + file.getAbsolutePath());
            return new DatabaseManager(c, Dialect.SQLITE, "SQLite (" + file.getName() + ")");
        } catch (ClassNotFoundException | SQLException e) {
            throw new DataAccessException("Could not open SQLite database: " + e.getMessage(), e);
        }
    }

    public Connection connection() { return connection; }

    public Dialect dialect() { return dialect; }

    public String description() { return description; }

    /** Runs {@code work} atomically: commit on success, rollback on any exception. Nested calls join the outer transaction. */
    public synchronized <T> T inTransaction(SqlWork<T> work) {
        boolean outermost = transactionDepth == 0;
        try {
            if (outermost) connection.setAutoCommit(false);
            transactionDepth++;
            T result = work.execute(connection);
            transactionDepth--;
            if (outermost) {
                connection.commit();
                connection.setAutoCommit(true);
            }
            return result;
        } catch (SQLException | RuntimeException e) {
            transactionDepth = Math.max(0, transactionDepth - 1);
            if (outermost) {
                try {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
            }
            if (e instanceof DataAccessException dae) throw dae;
            throw new DataAccessException("Transaction rolled back: " + e.getMessage(), e);
        }
    }

    /** Runs a single statement batch without an explicit transaction. */
    public synchronized <T> T run(SqlWork<T> work) {
        try {
            return work.execute(connection);
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    /** Executes an INSERT prepared with {@link Statement#RETURN_GENERATED_KEYS} and returns the new id. */
    public int executeInsert(PreparedStatement ps) throws SQLException {
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException ignored) {
            // some drivers do not support generated keys; fall through to dialect query
        }
        String sql = dialect == Dialect.SQLITE ? "SELECT last_insert_rowid()" : "SELECT LAST_INSERT_ID()";
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        }
        throw new SQLException("Could not obtain generated id");
    }

    public String autoIncrementPrimaryKey() {
        return dialect == Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "INT PRIMARY KEY AUTO_INCREMENT";
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("[JDBC] Error closing connection: " + e.getMessage());
        }
    }

    private static String firstLine(String message) {
        if (message == null) return "unknown error";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
