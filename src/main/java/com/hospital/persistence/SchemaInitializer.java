package com.hospital.persistence;

import com.hospital.model.Bed;
import com.hospital.model.BedType;
import com.hospital.model.Doctor;
import com.hospital.model.Role;
import com.hospital.model.Specialty;
import com.hospital.model.StaffUser;
import com.hospital.persistence.dao.BedDAO;
import com.hospital.persistence.dao.DoctorDAO;
import com.hospital.persistence.dao.StaffDAO;
import com.hospital.security.PasswordHasher;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/** Creates the tables (DDL) on first run and seeds the bed inventory and doctor roster. */
public class SchemaInitializer {

    private final DatabaseManager db;

    public SchemaInitializer(DatabaseManager db) {
        this.db = db;
    }

    public void initialize() {
        createTables();
        seedIfEmpty();
    }

    private void createTables() {
        String pk = db.autoIncrementPrimaryKey();
        List<String> ddl = List.of(
                "CREATE TABLE IF NOT EXISTS doctors ("
                        + "id " + pk + ", "
                        + "name VARCHAR(100) NOT NULL, "
                        + "specialty VARCHAR(40) NOT NULL, "
                        + "max_patients INT NOT NULL, "
                        + "active_patients INT NOT NULL DEFAULT 0, "
                        + "on_duty INT NOT NULL DEFAULT 1)",

                "CREATE TABLE IF NOT EXISTS patients ("
                        + "id " + pk + ", "
                        + "mrn VARCHAR(30) NOT NULL UNIQUE, "
                        + "full_name VARCHAR(100) NOT NULL, "
                        + "age INT NOT NULL, "
                        + "gender VARCHAR(10) NOT NULL, "
                        + "category VARCHAR(20) NOT NULL, "
                        + "chief_complaint VARCHAR(255) NOT NULL, "
                        + "trauma INT NOT NULL DEFAULT 0, "
                        + "heart_rate INT NOT NULL, "
                        + "systolic_bp INT NOT NULL, "
                        + "diastolic_bp INT NOT NULL, "
                        + "respiratory_rate INT NOT NULL, "
                        + "spo2 INT NOT NULL, "
                        + "temperature DOUBLE NOT NULL, "
                        + "pain_score INT NOT NULL, "
                        + "gcs INT NOT NULL, "
                        + "expected_resources INT NOT NULL, "
                        + "esi_level INT NOT NULL, "
                        + "severity_score DOUBLE NOT NULL, "
                        + "priority_score DOUBLE NOT NULL, "
                        + "status VARCHAR(30) NOT NULL, "
                        + "bed_id VARCHAR(15) NULL, "
                        + "doctor_id INT NULL, "
                        + "intake_time VARCHAR(30) NOT NULL, "
                        + "admitted_time VARCHAR(30) NULL, "
                        + "discharged_time VARCHAR(30) NULL, "
                        + "FOREIGN KEY (doctor_id) REFERENCES doctors(id))",

                "CREATE TABLE IF NOT EXISTS beds ("
                        + "bed_id VARCHAR(15) PRIMARY KEY, "
                        + "ward_name VARCHAR(60) NOT NULL, "
                        + "bed_type VARCHAR(20) NOT NULL, "
                        + "status VARCHAR(20) NOT NULL, "
                        + "patient_id INT NULL, "
                        + "updated_at VARCHAR(30) NOT NULL, "
                        + "FOREIGN KEY (patient_id) REFERENCES patients(id))",

                "CREATE TABLE IF NOT EXISTS admission_logs ("
                        + "id " + pk + ", "
                        + "patient_id INT NOT NULL, "
                        + "bed_id VARCHAR(15) NULL, "
                        + "doctor_id INT NULL, "
                        + "action VARCHAR(30) NOT NULL, "
                        + "details VARCHAR(500) NULL, "
                        + "priority_score DOUBLE NULL, "
                        + "performed_by VARCHAR(40) NULL, "
                        + "created_at VARCHAR(30) NOT NULL, "
                        + "FOREIGN KEY (patient_id) REFERENCES patients(id))",

                "CREATE TABLE IF NOT EXISTS alerts ("
                        + "id " + pk + ", "
                        + "severity VARCHAR(20) NOT NULL, "
                        + "category VARCHAR(40) NOT NULL, "
                        + "message VARCHAR(500) NOT NULL, "
                        + "patient_id INT NULL, "
                        + "created_at VARCHAR(30) NOT NULL, "
                        + "acknowledged INT NOT NULL DEFAULT 0)",

                "CREATE TABLE IF NOT EXISTS staff_users ("
                        + "id " + pk + ", "
                        + "username VARCHAR(40) NOT NULL UNIQUE, "
                        + "full_name VARCHAR(100) NOT NULL, "
                        + "role VARCHAR(20) NOT NULL, "
                        + "password_hash VARCHAR(200) NOT NULL, "
                        + "active INT NOT NULL DEFAULT 1, "
                        + "created_at VARCHAR(30) NOT NULL, "
                        + "last_login VARCHAR(30) NULL)",

                "CREATE TABLE IF NOT EXISTS vitals_history ("
                        + "id " + pk + ", "
                        + "patient_id INT NOT NULL, "
                        + "heart_rate INT NOT NULL, "
                        + "systolic_bp INT NOT NULL, "
                        + "diastolic_bp INT NOT NULL, "
                        + "respiratory_rate INT NOT NULL, "
                        + "spo2 INT NOT NULL, "
                        + "temperature DOUBLE NOT NULL, "
                        + "pain_score INT NOT NULL, "
                        + "gcs INT NOT NULL, "
                        + "esi_level INT NOT NULL, "
                        + "severity_score DOUBLE NOT NULL, "
                        + "recorded_by VARCHAR(40) NULL, "
                        + "recorded_at VARCHAR(30) NOT NULL, "
                        + "FOREIGN KEY (patient_id) REFERENCES patients(id))",

                "CREATE TABLE IF NOT EXISTS app_settings ("
                        + "setting_key VARCHAR(50) PRIMARY KEY, "
                        + "setting_value VARCHAR(200) NOT NULL)"
        );
        db.inTransaction(conn -> {
            try (Statement s = conn.createStatement()) {
                for (String sql : ddl) {
                    s.executeUpdate(sql);
                }
            }
            return null;
        });
        migrate();
    }

    /** Upgrades databases created by earlier versions of the app. */
    private void migrate() {
        addColumnIfMissing("admission_logs", "performed_by", "VARCHAR(40) NULL");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean exists = db.run(conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            for (String t : new String[]{table, table.toUpperCase()}) {
                try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, t, null)) {
                    while (rs.next()) {
                        if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) return true;
                    }
                }
            }
            return false;
        });
        if (!exists) {
            db.run(conn -> {
                try (Statement s = conn.createStatement()) {
                    return s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                }
            });
            System.out.println("[JDBC] Migrated: added " + table + "." + column);
        }
    }

    private void seedIfEmpty() {
        if (count("beds") == 0) {
            BedDAO bedDAO = new BedDAO(db);
            db.inTransaction(conn -> {
                seedWard(bedDAO, BedType.ICU, "Critical Care Unit A", 101, 4);
                seedWard(bedDAO, BedType.TRAUMA, "Trauma Resuscitation Wing", 201, 3);
                seedWard(bedDAO, BedType.MONITORED, "Cardiac Step-Down Unit", 301, 5);
                seedWard(bedDAO, BedType.PEDIATRIC, "Pediatric Emergency", 401, 3);
                seedWard(bedDAO, BedType.GENERAL, "General Emergency Floor", 501, 8);
                return null;
            });
            System.out.println("[JDBC] Seeded bed inventory.");
        }
        if (count("doctors") == 0) {
            DoctorDAO doctorDAO = new DoctorDAO(db);
            db.inTransaction(conn -> {
                doctorDAO.insert(new Doctor(0, "Dr. Sarah Jenkins", Specialty.EMERGENCY_MEDICINE, 5, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. Rahul Mehta", Specialty.EMERGENCY_MEDICINE, 5, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. Priya Patel", Specialty.CRITICAL_CARE, 3, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. Marcus Vance", Specialty.TRAUMA_SURGERY, 3, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. Ananya Rao", Specialty.CARDIOLOGY, 4, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. Emily Chen", Specialty.PEDIATRICS, 4, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. David Kim", Specialty.INTERNAL_MEDICINE, 5, 0, true));
                doctorDAO.insert(new Doctor(0, "Dr. Fatima Noor", Specialty.INTERNAL_MEDICINE, 5, 0, false));
                return null;
            });
            System.out.println("[JDBC] Seeded doctor roster.");
        }
        if (count("staff_users") == 0) {
            StaffDAO staffDAO = new StaffDAO(db);
            LocalDateTime now = LocalDateTime.now();
            db.inTransaction(conn -> {
                for (String[] u : DEMO_ACCOUNTS) {
                    staffDAO.insert(new StaffUser(0, u[0], u[2], Role.valueOf(u[3]),
                            PasswordHasher.hash(u[1]), true, now, null));
                }
                return null;
            });
            System.out.println("[JDBC] Seeded demo staff accounts (admin / doctor / nurse).");
        }
    }

    /** username, password, full name, role. Demo credentials - change them via the Staff screen. */
    public static final String[][] DEMO_ACCOUNTS = {
            {"admin", "admin123", "System Administrator", "ADMIN"},
            {"doctor", "doctor123", "Dr. Sarah Jenkins", "DOCTOR"},
            {"nurse", "nurse123", "Nurse Kavya Reddy", "NURSE"},
    };

    private static void seedWard(BedDAO dao, BedType type, String ward, int firstNumber, int count) {
        for (int i = 0; i < count; i++) {
            dao.insert(new Bed(type.getPrefix() + "-" + (firstNumber + i), ward, type));
        }
    }

    private int count(String table) {
        return db.run(conn -> {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** Deletes all patient activity and returns beds and doctors to their initial state. */
    public void resetActivity() {
        db.inTransaction(conn -> {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("UPDATE beds SET status = 'AVAILABLE', patient_id = NULL");
                s.executeUpdate("UPDATE doctors SET active_patients = 0");
                s.executeUpdate("DELETE FROM admission_logs");
                s.executeUpdate("DELETE FROM vitals_history");
                s.executeUpdate("DELETE FROM alerts");
                s.executeUpdate("DELETE FROM patients");
            }
            return null;
        });
    }
}
