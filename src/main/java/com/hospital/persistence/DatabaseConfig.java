package com.hospital.persistence;

import java.util.Locale;

/**
 * Database settings. Read from JVM system properties first, then environment variables:
 *
 * <pre>
 *   -Ddb.mode=auto|mysql|sqlite        HOSPITAL_DB_MODE      (default: auto)
 *   -Ddb.mysql.url=jdbc:mysql://...    HOSPITAL_DB_URL
 *   -Ddb.user=root                     HOSPITAL_DB_USER
 *   -Ddb.password=secret               HOSPITAL_DB_PASSWORD
 *   -Ddb.sqlite.file=hospital.db       HOSPITAL_DB_FILE
 * </pre>
 *
 * In {@code auto} mode MySQL is tried first and the embedded SQLite file is used if
 * MySQL is not reachable, so the app always runs with zero setup.
 */
public record DatabaseConfig(Mode mode, String mysqlUrl, String user, String password, String sqliteFile) {

    public enum Mode { AUTO, MYSQL, SQLITE }

    public static final String DEFAULT_MYSQL_URL =
            "jdbc:mysql://localhost:3306/hospital_triage_db?createDatabaseIfNotExist=true"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=2000";
    public static final String DEFAULT_SQLITE_FILE = "data/hospital_triage.db";

    public static DatabaseConfig fromEnvironment() {
        String mode = setting("db.mode", "HOSPITAL_DB_MODE", "auto");
        return new DatabaseConfig(
                Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT)),
                setting("db.mysql.url", "HOSPITAL_DB_URL", DEFAULT_MYSQL_URL),
                setting("db.user", "HOSPITAL_DB_USER", "root"),
                setting("db.password", "HOSPITAL_DB_PASSWORD", ""),
                setting("db.sqlite.file", "HOSPITAL_DB_FILE", DEFAULT_SQLITE_FILE));
    }

    public static DatabaseConfig sqlite(String file) {
        return new DatabaseConfig(Mode.SQLITE, DEFAULT_MYSQL_URL, "root", "", file);
    }

    private static String setting(String property, String envVar, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(envVar);
        return value == null || value.isBlank() ? fallback : value;
    }
}
