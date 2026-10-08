package com.hospital;

import com.hospital.model.Alert;
import com.hospital.model.AppSettings;
import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Patient;
import com.hospital.model.PatientIntakeForm;
import com.hospital.model.Permission;
import com.hospital.model.Role;
import com.hospital.model.StaffUser;
import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;
import com.hospital.persistence.DatabaseConfig;
import com.hospital.persistence.DatabaseManager;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.security.PasswordHasher;
import com.hospital.service.AuthService;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;

import java.io.File;
import java.sql.Statement;
import java.util.List;

import static com.hospital.SelfTestRunner.check;
import static com.hospital.SelfTestRunner.deleteQuietly;
import static com.hospital.SelfTestRunner.freshManager;
import static com.hospital.SelfTestRunner.run;
import static com.hospital.SelfTestRunner.tempDbFile;

/** Tests for login/roles, re-assessment, transfers, settings, search and staff management. */
final class FeatureTests {

    private FeatureTests() {}

    static void runAll() {
        run("Password hashing: salted, verifies, rejects wrong/garbled", FeatureTests::passwordHashing);
        run("Login: demo accounts, wrong password, lockout after 5 attempts", FeatureTests::login);
        run("Role permissions enforced (nurse cannot discharge/reset/manage staff)", FeatureTests::permissions);
        run("Re-assessment re-triages, moves patient up the heap, records history", FeatureTests::reassess);
        run("Bed transfer moves patient, frees old bed for cleaning, is audited", FeatureTests::transfer);
        run("Settings persist across restarts and are validated", FeatureTests::settings);
        run("Patient search by name/MRN incl. discharged; LIKE wildcards escaped", FeatureTests::search);
        run("Staff management: create, duplicates, last-admin protection, own password", FeatureTests::staff);
        run("Migration adds performed_by to an old admission_logs table", FeatureTests::migration);
    }

    private static void passwordHashing() {
        String h1 = PasswordHasher.hash("secret1");
        String h2 = PasswordHasher.hash("secret1");
        check(!h1.equals(h2), "same password must produce different salted hashes");
        check(PasswordHasher.verify("secret1", h1), "correct password rejected");
        check(!PasswordHasher.verify("secret2", h1), "wrong password accepted");
        check(!PasswordHasher.verify("secret1", "garbage"), "garbled hash accepted");
        check(!PasswordHasher.verify(null, h1), "null password accepted");
    }

    private static void login() {
        File f = tempDbFile("auth");
        try (HospitalManager m = freshManager(f, null, null)) {
            check(m.currentUser() == null, "nobody should be logged in at start");
            check(!m.hasPermission(Permission.REGISTER_PATIENT), "anonymous user has permissions");
            check(!m.registerPatient(form("Anon Person")).success(), "anonymous registration allowed");

            check(!m.login("nurse", "wrong").success(), "wrong password accepted");
            check(m.login("NURSE", "nurse123").success(), "username should be case-insensitive");
            check(m.currentUser().getRole() == Role.NURSE, "wrong role");
            m.logout();
            check(m.currentUser() == null, "logout failed");

            for (int i = 0; i < AuthService.MAX_FAILED_ATTEMPTS; i++) m.login("doctor", "bad-" + i);
            OperationResult locked = m.login("doctor", "doctor123");
            check(!locked.success() && locked.message().contains("Too many"), "account should be locked: " + locked.message());
        } finally {
            deleteQuietly(f);
        }
    }

    private static void permissions() {
        File f = tempDbFile("perm");
        try (HospitalManager m = freshManager(f, "nurse", "nurse123")) {
            check(m.registerPatient(form("Nina Nurse-Case")).success(), "nurse should register patients");
            check(m.admitNext().success(), "nurse should admit");
            Patient p = m.admittedPatients().get(0);
            OperationResult d = m.dischargePatient(p.getId());
            check(!d.success() && d.message().startsWith("Permission denied"), "nurse discharged a patient");
            check(!m.transferPatient(p.getId(), "GEN-505").success(), "nurse transferred a patient");
            check(!m.resetDemoData().success(), "nurse reset data");
            check(!m.updateSettings(AppSettings.defaults()).success(), "nurse changed settings");
            check(m.listStaff().isEmpty(), "nurse can see staff accounts");
            check(!m.createStaff("evil", "Evil", Role.ADMIN, "password").success(), "nurse created an admin");
            m.logout();
            check(m.login("doctor", "doctor123").success(), "doctor login");
            check(m.dischargePatient(p.getId()).success(), "doctor should discharge");
        } finally {
            deleteQuietly(f);
        }
    }

    private static void reassess() {
        File f = tempDbFile("reassess");
        try (HospitalManager m = freshManager(f, "nurse", "nurse123")) {
            Patient first = m.registerPatient(new PatientIntakeForm("Urgent Case", 40, "Male", "Abdominal pain", false,
                    90, 125, 80, 18, 97, 37.2, 5, 15, 3)).patient();
            Patient minor = m.registerPatient(form("Minor Case")).patient();
            check(minor.getTriageLevel() == TriageLevel.NON_URGENT, "minor case should be ESI 5");
            check(m.waitingQueue().get(0) == first, "ESI 3 should lead initially");

            OperationResult r = m.reassessPatient(minor.getId(), new Vitals(130, 85, 50, 30, 86, 38.9, 6, 13), 3);
            check(r.success(), "reassess failed: " + r.message());
            check(minor.getTriageLevel().getEsi() <= 2, "deteriorated patient should be ESI 1-2, got " + minor.getTriageLevel());
            check(m.waitingQueue().get(0) == minor, "deteriorated patient should now lead the queue");
            check(m.vitalsHistory(minor.getId()).size() == 2, "vitals history should have 2 entries");
            check(m.recentAlerts().stream().anyMatch(a -> a.getCategory() == Alert.Category.DETERIORATION),
                    "no deterioration alert");
            List<LogEntry> timeline = m.patientTimeline(minor.getId());
            check(timeline.stream().anyMatch(e -> e.action().equals("REASSESSED") && "nurse".equals(e.performedBy())),
                    "reassessment not audited with the nurse's username");

            OperationResult bad = m.reassessPatient(minor.getId(), new Vitals(80, 100, 120, 16, 98, 37, 0, 15), 1);
            check(!bad.success(), "diastolic > systolic should be rejected");
            check(minor.getTriageLevel().getEsi() <= 2, "rejected reassessment must not change the patient");
        } finally {
            deleteQuietly(f);
        }
    }

    private static void transfer() {
        File f = tempDbFile("transfer");
        try (HospitalManager m = freshManager(f, "doctor", "doctor123")) {
            m.registerPatient(new PatientIntakeForm("Crit Case", 55, "Female", "Collapse", false,
                    35, 70, 40, 6, 80, 35.5, 0, 6, 5));
            check(m.admitNext().success(), "admit failed");
            Patient p = m.admittedPatients().get(0);
            String oldBed = p.getAssignedBedId();
            check(m.findBed(oldBed).getType() == BedType.ICU, "ESI 1 should be in ICU");

            List<Bed> options = m.transferOptions(p.getId());
            check(!options.isEmpty() && options.stream().noneMatch(b -> b.getType() == BedType.PEDIATRIC),
                    "adult transfer options must exclude pediatric beds");
            check(options.get(0).getType() == BedType.ICU, "suitable bed types should be offered first");

            check(!m.transferPatient(p.getId(), "PED-401").success(), "adult moved to pediatric bed");
            check(!m.transferPatient(p.getId(), oldBed).success(), "transfer to same bed allowed");
            OperationResult r = m.transferPatient(p.getId(), "MON-301");
            check(r.success(), "transfer failed: " + r.message());
            check("MON-301".equals(p.getAssignedBedId()), "patient bed not updated");
            check(m.findBed("MON-301").getStatus() == BedStatus.OCCUPIED, "new bed not occupied");
            check(m.findBed(oldBed).getStatus() == BedStatus.CLEANING, "old bed should be cleaning");
            check(m.patientTimeline(p.getId()).stream().anyMatch(e -> e.action().equals("TRANSFERRED")), "transfer not logged");
        }
        try (HospitalManager m = freshManager(f, "doctor", "doctor123")) {
            Patient reloaded = m.admittedPatients().get(0);
            check("MON-301".equals(reloaded.getAssignedBedId()), "transfer not persisted");
        } finally {
            deleteQuietly(f);
        }
    }

    private static void settings() {
        File f = tempDbFile("settings");
        try (HospitalManager m = freshManager(f, "admin", "admin123")) {
            check(m.settings().equals(AppSettings.defaults()), "fresh database should use defaults");
            check(!m.updateSettings(new AppSettings(true, 1.5, false, 5, 15)).success(), "threshold 150% accepted");
            check(!m.updateSettings(new AppSettings(true, 0.8, false, 1, 15)).success(), "refresh 1 s accepted");
            check(m.updateSettings(new AppSettings(true, 0.8, true, 10, 20)).success(), "valid settings rejected");
        }
        try (HospitalManager m = freshManager(f, "admin", "admin123")) {
            check(m.settings().equals(new AppSettings(true, 0.8, true, 10, 20)), "settings not persisted: " + m.settings());
            m.registerPatient(form("Auto Admit"));
            m.tick(); // auto-admit is on
            check(m.waitingQueue().isEmpty(), "auto-admit should have placed the patient");
            check(m.patientTimeline(m.admittedPatients().get(0).getId()).stream()
                    .anyMatch(e -> e.action().equals("ADMITTED") && "auto-admit".equals(e.performedBy())),
                    "auto-admission should be audited as auto-admit");
        } finally {
            deleteQuietly(f);
        }
    }

    private static void search() {
        File f = tempDbFile("search");
        try (HospitalManager m = freshManager(f, "admin", "admin123")) {
            Patient a = m.registerPatient(form("Priya Sharma")).patient();
            m.registerPatient(form("Rohan Gupta"));
            m.admitPatient(a.getId());
            m.dischargePatient(a.getId());

            List<Patient> byName = m.searchPatients("sharma", 10);
            check(byName.size() == 1 && byName.get(0).getId() == a.getId(), "name search failed");
            check(m.searchPatients(a.getMrn().toLowerCase(), 10).size() == 1, "MRN search failed");
            check(m.searchPatients("%", 10).isEmpty(), "'%' must be a literal, not a wildcard");
            check(m.searchPatients("_", 10).isEmpty(), "'_' must be a literal, not a wildcard");
            check(m.searchPatients("", 10).size() == 2, "empty query should list recent patients");
            check(m.findPatientRecord(a.getId()).isPresent(), "discharged patient record not found");
            check(m.findPatient(a.getId()) == a, "registry lookup changed");
        } finally {
            deleteQuietly(f);
        }
    }

    private static void staff() {
        File f = tempDbFile("staff");
        try (HospitalManager m = freshManager(f, "admin", "admin123")) {
            check(m.listStaff().size() == 3, "3 demo accounts expected");
            check(m.createStaff("new.nurse", "New Nurse", Role.NURSE, "pass1234").success(), "create failed");
            check(!m.createStaff("new.nurse", "Dup", Role.NURSE, "pass1234").success(), "duplicate username accepted");
            check(!m.createStaff("x", "Short", Role.NURSE, "pass1234").success(), "2-char username accepted");
            check(!m.createStaff("ok.name", "Weak", Role.NURSE, "123").success(), "short password accepted");

            StaffUser admin = m.currentUser();
            check(!m.setStaffActive(admin.getId(), false).success(), "admin deactivated themselves");
            check(!m.changeStaffRole(admin.getId(), Role.NURSE).success(), "last admin demoted");

            StaffUser created = m.listStaff().stream().filter(u -> u.getUsername().equals("new.nurse")).findFirst().orElseThrow();
            check(m.setStaffActive(created.getId(), false).success(), "deactivate failed");
            m.logout();
            check(!m.login("new.nurse", "pass1234").success(), "deactivated account logged in");

            check(m.login("admin", "admin123").success(), "admin re-login");
            check(!m.changeOwnPassword("wrong", "newpass1").success(), "wrong old password accepted");
            check(m.changeOwnPassword("admin123", "newpass1").success(), "change own password failed");
            m.logout();
            check(m.login("admin", "newpass1").success(), "new password not accepted");
        } finally {
            deleteQuietly(f);
        }
    }

    private static void migration() throws Exception {
        File f = tempDbFile("migrate");
        try {
            try (DatabaseManager db = DatabaseManager.connect(DatabaseConfig.sqlite(f.getPath()));
                 Statement s = db.connection().createStatement()) {
                s.executeUpdate("CREATE TABLE admission_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, patient_id INT NOT NULL, "
                        + "bed_id VARCHAR(15) NULL, doctor_id INT NULL, action VARCHAR(30) NOT NULL, details VARCHAR(500) NULL, "
                        + "priority_score DOUBLE NULL, created_at VARCHAR(30) NOT NULL)");
            }
            try (HospitalManager m = freshManager(f, "admin", "admin123")) {
                OperationResult r = m.registerPatient(form("Legacy Case"));
                check(r.success(), "registration on migrated DB failed: " + r.message());
                check("admin".equals(m.patientTimeline(r.patient().getId()).get(0).performedBy()), "performed_by not stored");
            }
        } finally {
            deleteQuietly(f);
        }
    }

    private static PatientIntakeForm form(String name) {
        return new PatientIntakeForm(name, 30, "Female", "Sore throat", false, 80, 120, 80, 16, 98, 37.0, 2, 15, 0);
    }
}
