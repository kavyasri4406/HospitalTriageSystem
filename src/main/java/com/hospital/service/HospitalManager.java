package com.hospital.service;

import com.hospital.algorithms.BedMatchingAlgorithm;
import com.hospital.algorithms.BedMatchingAlgorithm.BedMatch;
import com.hospital.algorithms.DoctorAllocationAlgorithm;
import com.hospital.algorithms.EsiScoringAlgorithm;
import com.hospital.algorithms.TriageAssessment;
import com.hospital.algorithms.WaitTimeAgingAlgorithm;
import com.hospital.algorithms.WaitTimePredictor;
import com.hospital.model.Alert;
import com.hospital.model.AlertSeverity;
import com.hospital.model.AppSettings;
import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.model.PatientFactory;
import com.hospital.model.PatientIntakeForm;
import com.hospital.model.PatientStatus;
import com.hospital.model.Permission;
import com.hospital.model.Role;
import com.hospital.model.StaffUser;
import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;
import com.hospital.model.VitalsRecord;
import com.hospital.persistence.DataAccessException;
import com.hospital.persistence.DatabaseConfig;
import com.hospital.persistence.DatabaseManager;
import com.hospital.persistence.SchemaInitializer;
import com.hospital.persistence.dao.AdmissionLogDAO;
import com.hospital.persistence.dao.AdmissionLogDAO.Action;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.persistence.dao.AlertDAO;
import com.hospital.persistence.dao.BedDAO;
import com.hospital.persistence.dao.DoctorDAO;
import com.hospital.persistence.dao.PatientDAO;
import com.hospital.persistence.dao.SettingsDAO;
import com.hospital.persistence.dao.StaffDAO;
import com.hospital.persistence.dao.VitalsHistoryDAO;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Facade over the whole backend. The JavaFX frontend talks only to this class.
 * Every state-changing operation checks the logged-in user's {@link Permission}.
 *
 * <pre>
 *   register  : validate -> create (age-group subclass) -> ESI score -> persist (+vitals history) -> priority queue -> alerts
 *   reassess  : new vitals -> ESI again -> heap update(key) -> persist -> deterioration alerts
 *   admit     : poll queue -> match bed -> dispatch doctor -> persist in ONE transaction -> alerts
 *   transfer  : old bed -> CLEANING, new bed -> OCCUPIED, in ONE transaction
 *   discharge : free bed (to CLEANING) -> release doctor -> persist -> log
 *   tick      : wait-time aging -> re-heapify -> optional auto-admit -> overdue / capacity alerts
 * </pre>
 */
public class HospitalManager implements AutoCloseable {

    private static final String AUTO_ADMIT_ACTOR = "auto-admit";

    private final DatabaseManager db;
    private final HospitalClock clock = new HospitalClock();
    private final ValidationService validation = new ValidationService();
    private final AuthService auth;
    private final SettingsService settings;
    private final TriageService triage;
    private final BedAllocationService beds;
    private final DoctorDispatchService doctors;
    private final PatientManagementService patients;
    private final AlertService alerts;
    private final AnalyticsService analytics;
    private final PatientSimulator simulator = new PatientSimulator();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public HospitalManager(DatabaseManager db) {
        this.db = db;
        this.auth = new AuthService(new StaffDAO(db));
        this.settings = new SettingsService(new SettingsDAO(db));
        this.triage = new TriageService(new EsiScoringAlgorithm(), new WaitTimeAgingAlgorithm(), clock);
        this.beds = new BedAllocationService(new BedDAO(db), new BedMatchingAlgorithm(), clock);
        this.doctors = new DoctorDispatchService(new DoctorDAO(db), new DoctorAllocationAlgorithm());
        this.patients = new PatientManagementService(new PatientDAO(db), new AdmissionLogDAO(db),
                new VitalsHistoryDAO(db), clock, auth::actorName);
        this.alerts = new AlertService(new AlertDAO(db), clock);
        this.analytics = new AnalyticsService(triage, beds, doctors, patients, clock);
    }

    public static HospitalManager create(DatabaseConfig config) {
        HospitalManager manager = new HospitalManager(DatabaseManager.connect(config));
        manager.start();
        return manager;
    }

    /** Creates tables if needed and rebuilds all in-memory structures from the database. */
    public void start() {
        new SchemaInitializer(db).initialize();
        settings.load();
        beds.load();
        doctors.load();
        alerts.load();
        triage.clear();
        for (Patient p : patients.loadActive()) {
            if (p.getStatus() == PatientStatus.WAITING) {
                TriageAssessment a = triage.assess(p);
                triage.enqueue(p, a.reasons());
            }
        }
        triage.reprioritizeAll();
        System.out.printf("[Manager] Ready: %d waiting, %d admitted, %d beds, %d doctors%n",
                triage.waitingCount(), patients.admitted().size(), beds.totalCount(), doctors.allDoctors().size());
    }

    // =====================================================================
    // Authentication, roles and staff accounts
    // =====================================================================

    public OperationResult login(String username, String password) {
        OperationResult r = auth.login(username, password);
        if (r.success()) fireChanged();
        return r;
    }

    public void logout() {
        auth.logout();
        fireChanged();
    }

    /** Logged-in user, or null on the login screen. */
    public StaffUser currentUser() { return auth.currentUser(); }

    /** Web sessions: act as an already-logged-in user for the current request. False if the account is gone or inactive. */
    public boolean resumeSession(int staffId) { return auth.resume(staffId); }

    public boolean hasPermission(Permission permission) { return auth.hasPermission(permission); }

    private OperationResult denied(Permission permission) {
        StaffUser u = auth.currentUser();
        if (u == null) return OperationResult.fail("Please log in first.");
        return OperationResult.fail("Permission denied: a " + u.getRole().getDisplayName().toLowerCase(Locale.ROOT)
                + " account cannot " + permission.getDescription().toLowerCase(Locale.ROOT) + ".");
    }

    /** Staff accounts; empty unless the current user may manage staff. */
    public List<StaffUser> listStaff() {
        return hasPermission(Permission.MANAGE_STAFF) ? auth.listStaff() : List.of();
    }

    public OperationResult createStaff(String username, String fullName, Role role, String password) {
        if (!hasPermission(Permission.MANAGE_STAFF)) return denied(Permission.MANAGE_STAFF);
        return changed(auth.createStaff(username, fullName, role, password));
    }

    public OperationResult setStaffActive(int staffId, boolean active) {
        if (!hasPermission(Permission.MANAGE_STAFF)) return denied(Permission.MANAGE_STAFF);
        return changed(auth.setActive(staffId, active));
    }

    public OperationResult changeStaffRole(int staffId, Role role) {
        if (!hasPermission(Permission.MANAGE_STAFF)) return denied(Permission.MANAGE_STAFF);
        return changed(auth.changeRole(staffId, role));
    }

    public OperationResult resetStaffPassword(int staffId, String newPassword) {
        if (!hasPermission(Permission.MANAGE_STAFF)) return denied(Permission.MANAGE_STAFF);
        return auth.resetPassword(staffId, newPassword);
    }

    /** Any logged-in user may change their own password. */
    public OperationResult changeOwnPassword(String oldPassword, String newPassword) {
        return auth.changeOwnPassword(oldPassword, newPassword);
    }

    // =====================================================================
    // Settings
    // =====================================================================

    public AppSettings settings() { return settings.get(); }

    public OperationResult updateSettings(AppSettings newSettings) {
        if (!hasPermission(Permission.MANAGE_SETTINGS)) return denied(Permission.MANAGE_SETTINGS);
        OperationResult r = settings.update(newSettings);
        if (r.success()) {
            alerts.clearOnce("occupancy-high"); // re-evaluate against the new threshold
            fireChanged();
        }
        return r;
    }

    // =====================================================================
    // Patient intake and re-assessment
    // =====================================================================

    public ValidationResult validate(PatientIntakeForm form) {
        return validation.validate(form);
    }

    /** Live ESI preview for the intake form; returns null while the form is invalid. */
    public TriageAssessment previewTriage(PatientIntakeForm form) {
        if (!validation.validate(form).isValid()) return null;
        return triage.preview(PatientFactory.create(form, clock.now()));
    }

    public OperationResult registerPatient(PatientIntakeForm form) {
        if (!hasPermission(Permission.REGISTER_PATIENT)) return denied(Permission.REGISTER_PATIENT);
        OperationResult r = doRegister(form, clock.now());
        if (r.success()) fireChanged();
        return r;
    }

    public OperationResult registerPatient(PatientIntakeForm form, LocalDateTime intakeTime) {
        if (!hasPermission(Permission.REGISTER_PATIENT)) return denied(Permission.REGISTER_PATIENT);
        OperationResult r = doRegister(form, intakeTime);
        if (r.success()) fireChanged();
        return r;
    }

    private OperationResult doRegister(PatientIntakeForm form, LocalDateTime intakeTime) {
        ValidationResult v = validation.validate(form);
        if (!v.isValid()) return OperationResult.invalid(v.getErrors());

        Patient patient = patients.create(form, intakeTime);
        TriageAssessment assessment = triage.assess(patient);
        try {
            db.inTransaction(conn -> {
                patients.save(patient);
                patients.recordVitals(patient);
                patients.log(patient, Action.REGISTERED, patient.getTriageLevel() + ": " + String.join("; ", assessment.reasons()));
                return null;
            });
        } catch (DataAccessException e) {
            return OperationResult.fail("Could not save patient: " + e.getMessage());
        }
        patients.track(patient);
        triage.enqueue(patient, assessment.reasons());

        if (patient.getTriageLevel().getEsi() == 1) {
            alerts.raise(AlertSeverity.CRITICAL, Alert.Category.CRITICAL_ARRIVAL,
                    "ESI-1 RESUSCITATION: " + patient.getFullName() + " (" + patient.getChiefComplaint() + ") needs immediate care",
                    patient.getId());
        } else if (patient.getTriageLevel().getEsi() == 2) {
            alerts.raise(AlertSeverity.WARNING, Alert.Category.CRITICAL_ARRIVAL,
                    "ESI-2 EMERGENT: " + patient.getFullName() + " - " + patient.getChiefComplaint(), patient.getId());
        }
        String msg = String.format("%s registered as %s (severity %.1f)", patient.getFullName(),
                patient.getTriageLevel(), patient.getSeverityScore());
        return new OperationResult(true, msg, patient, assessment, List.of());
    }

    /**
     * Records new vitals for a waiting or admitted patient and re-runs ESI triage.
     * Waiting patients move within the priority heap; deterioration raises an alert, and an admitted
     * patient who now needs a higher-acuity bed gets a transfer suggestion.
     */
    public OperationResult reassessPatient(int patientId, Vitals newVitals, int expectedResources) {
        if (!hasPermission(Permission.REASSESS_PATIENT)) return denied(Permission.REASSESS_PATIENT);
        Patient p = patients.findById(patientId);
        if (p == null || !p.getStatus().isActive()) return OperationResult.fail("Patient is not in the department.");
        if (newVitals == null) return OperationResult.fail("Vitals are required.");

        PatientIntakeForm check = new PatientIntakeForm(p.getFullName(), p.getAge(), p.getGender(), p.getChiefComplaint(),
                p.isTrauma(), newVitals.heartRate(), newVitals.systolicBp(), newVitals.diastolicBp(),
                newVitals.respiratoryRate(), newVitals.spo2(), newVitals.temperature(), newVitals.painScore(),
                newVitals.gcs(), expectedResources);
        ValidationResult v = validation.validate(check);
        if (!v.isValid()) return OperationResult.invalid(v.getErrors());

        Vitals oldVitals = p.getVitals();
        int oldResources = p.getExpectedResources();
        TriageLevel oldLevel = p.getTriageLevel();
        double oldSeverity = p.getSeverityScore();

        p.updateVitals(newVitals, expectedResources);
        TriageAssessment a = triage.reassess(p);
        String change = String.format("ESI %d -> ESI %d, severity %.1f -> %.1f",
                oldLevel.getEsi(), a.level().getEsi(), oldSeverity, a.severityScore());
        try {
            db.inTransaction(conn -> {
                patients.update(p);
                patients.recordVitals(p);
                patients.log(p, Action.REASSESSED, change + "; " + String.join("; ", a.reasons()));
                return null;
            });
        } catch (RuntimeException e) {
            p.updateVitals(oldVitals, oldResources);
            triage.reassess(p); // deterministic: restores the previous level, scores and heap position
            return OperationResult.fail("Re-assessment failed and was rolled back: " + e.getMessage());
        }

        if (a.level().getEsi() < oldLevel.getEsi()) {
            alerts.raise(a.level().isCritical() ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
                    Alert.Category.DETERIORATION,
                    "DETERIORATING: " + p.getFullName() + " " + oldLevel + " -> " + a.level()
                            + (a.reasons().isEmpty() ? "" : " (" + a.reasons().get(0) + ")"), p.getId());
        } else if (a.level().getEsi() > oldLevel.getEsi()) {
            alerts.raise(AlertSeverity.INFO, Alert.Category.DETERIORATION,
                    "Improving: " + p.getFullName() + " " + oldLevel + " -> " + a.level(), p.getId());
        }
        if (p.getStatus() == PatientStatus.ADMITTED) {
            Bed bed = beds.findById(p.getAssignedBedId());
            List<BedType> allowed = beds.candidateBedTypes(p);
            if (bed != null && !allowed.isEmpty() && !allowed.contains(bed.getType())) {
                alerts.raise(AlertSeverity.WARNING, Alert.Category.ALLOCATION,
                        p.getFullName() + " in " + bed.getBedId() + " now needs a " + allowed.get(0).getDisplayName()
                                + " bed - consider a transfer", p.getId());
            }
        }
        fireChanged();
        return new OperationResult(true, "Re-assessed " + p.getFullName() + ": " + change, p, a, List.of());
    }

    public List<VitalsRecord> vitalsHistory(int patientId) { return patients.vitalsHistory(patientId); }

    /** A random but realistic intake form, used by the "Fill sample" button. */
    public PatientIntakeForm randomIntakeForm() {
        return simulator.randomArrival();
    }

    public List<OperationResult> simulateArrivals(int count) {
        if (!hasPermission(Permission.SIMULATE)) return List.of(denied(Permission.SIMULATE));
        List<OperationResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PatientIntakeForm form = simulator.randomArrival();
            LocalDateTime arrived = clock.now().minusMinutes(simulator.randomMinutesAlreadyWaiting());
            results.add(doRegister(form, arrived));
        }
        triage.reprioritizeAll();
        fireChanged();
        return results;
    }

    // =====================================================================
    // Admission: bed + doctor allocation
    // =====================================================================

    public OperationResult admitNext() {
        if (!hasPermission(Permission.ADMIT_PATIENT)) return denied(Permission.ADMIT_PATIENT);
        triage.reprioritizeAll();
        Patient next = triage.peekNext();
        if (next == null) return OperationResult.fail("No patients are waiting.");
        return admitAndNotify(next);
    }

    public OperationResult admitPatient(int patientId) {
        if (!hasPermission(Permission.ADMIT_PATIENT)) return denied(Permission.ADMIT_PATIENT);
        if (!triage.isWaiting(patientId)) return OperationResult.fail("Patient is not in the waiting queue.");
        return admitAndNotify(patients.findById(patientId));
    }

    /**
     * Admits waiting patients in priority order until beds run out. A patient whose bed type
     * is full is skipped (so they do not block lower-priority patients who need a different bed).
     */
    public OperationResult autoAllocate() {
        if (!hasPermission(Permission.ADMIT_PATIENT)) return denied(Permission.ADMIT_PATIENT);
        OperationResult r = autoAllocateAs(auth.actorName());
        fireChanged();
        return r;
    }

    private OperationResult autoAllocateAs(String actor) {
        triage.reprioritizeAll();
        int admitted = 0;
        int skipped = 0;
        for (Patient p : triage.waitingInPriorityOrder()) {
            if (beds.availableCount() == 0) break;
            if (admit(p, false, actor).success()) admitted++;
            else skipped++;
        }
        if (admitted == 0) return OperationResult.fail("No patients could be placed (" + skipped + " waiting for suitable beds).");
        return OperationResult.ok("Auto-allocated " + admitted + " patient(s)"
                + (skipped > 0 ? "; " + skipped + " still waiting for a suitable bed." : "."));
    }

    private OperationResult admitAndNotify(Patient patient) {
        OperationResult r = admit(patient, true, auth.actorName());
        fireChanged();
        return r;
    }

    private OperationResult admit(Patient patient, boolean alertOnNoBed, String actor) {
        BedMatch match = beds.findBed(patient);
        if (!match.found()) {
            if (alertOnNoBed) {
                alerts.raise(AlertSeverity.WARNING, Alert.Category.BED_CAPACITY,
                        "No bed for " + patient.getFullName() + " (" + patient.getTriageLevel() + "): " + match.explanation(),
                        patient.getId());
            }
            return OperationResult.fail(match.explanation());
        }
        Bed bed = match.bed();
        Doctor doctor = doctors.selectDoctor(patient);
        LocalDateTime now = clock.now();

        try {
            db.inTransaction(conn -> {
                beds.occupy(bed, patient);
                if (doctor != null) doctors.assign(doctor);
                patient.markAdmitted(bed.getBedId(), doctor == null ? null : doctor.getId(), now);
                patients.update(patient);
                patients.log(patient, Action.ADMITTED, match.explanation()
                        + (doctor == null ? "; no doctor available" : "; doctor " + doctor.getName()), actor);
                return null;
            });
        } catch (RuntimeException e) {
            // The database rolled back; reload beds/doctors so memory matches it again.
            beds.load();
            doctors.load();
            patient.restoreDisposition(PatientStatus.WAITING, null, null, null, null);
            return OperationResult.fail("Admission failed and was rolled back: " + e.getMessage());
        }
        triage.remove(patient.getId());
        alerts.clearOnce("overdue-" + patient.getId());

        if (match.fallback()) {
            alerts.raise(AlertSeverity.WARNING, Alert.Category.ALLOCATION,
                    patient.getFullName() + ": " + match.explanation() + " (" + bed.getBedId() + ")", patient.getId());
        }
        if (doctor == null) {
            alerts.raise(AlertSeverity.CRITICAL, Alert.Category.NO_DOCTOR_AVAILABLE,
                    "No doctor available for " + patient.getFullName() + " in " + bed.getBedId()
                            + " - needs " + doctors.requiredSpecialty(patient).getDisplayName(), patient.getId());
        }
        checkCapacity(bed.getType());
        String msg = String.format("Admitted %s to %s%s", patient.getFullName(), bed.getBedId(),
                doctor == null ? " (awaiting doctor)" : " under " + doctor.getName());
        return OperationResult.ok(msg, patient);
    }

    // =====================================================================
    // Transfer, discharge and bed / doctor management
    // =====================================================================

    /**
     * Available beds an admitted patient could be moved to: clinically suitable types first
     * (in fallback order), then any other free bed. Pediatric beds are never offered to adults.
     */
    public List<Bed> transferOptions(int patientId) {
        Patient p = patients.findById(patientId);
        if (p == null || p.getStatus() != PatientStatus.ADMITTED) return List.of();
        List<BedType> preferred = beds.candidateBedTypes(p);
        List<Bed> options = new ArrayList<>();
        for (Bed b : beds.allBeds()) {
            if (!b.isAvailable() || b.getBedId().equals(p.getAssignedBedId())) continue;
            if (b.getType() == BedType.PEDIATRIC && p.getAge() > PatientFactory.PEDIATRIC_MAX_AGE) continue;
            options.add(b);
        }
        options.sort(Comparator.comparingInt((Bed b) -> {
            int i = preferred.indexOf(b.getType());
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(Bed::getBedId));
        return options;
    }

    public OperationResult transferPatient(int patientId, String targetBedId) {
        if (!hasPermission(Permission.TRANSFER_PATIENT)) return denied(Permission.TRANSFER_PATIENT);
        Patient p = patients.findById(patientId);
        if (p == null || p.getStatus() != PatientStatus.ADMITTED) return OperationResult.fail("Patient is not currently admitted.");
        Bed from = beds.findById(p.getAssignedBedId());
        Bed to = beds.findById(targetBedId);
        if (from == null) return OperationResult.fail("Patient's current bed is unknown.");
        if (to == null) return OperationResult.fail("Unknown bed " + targetBedId + ".");
        if (to == from) return OperationResult.fail("Patient is already in " + targetBedId + ".");
        if (!to.isAvailable()) return OperationResult.fail(targetBedId + " is not available (" + to.getStatus() + ").");
        if (to.getType() == BedType.PEDIATRIC && p.getAge() > PatientFactory.PEDIATRIC_MAX_AGE) {
            return OperationResult.fail("Adults cannot be placed in pediatric beds.");
        }
        String fromId = from.getBedId();
        try {
            db.inTransaction(conn -> {
                beds.transfer(p, from, to);
                p.moveToBed(to.getBedId());
                patients.update(p);
                patients.log(p, Action.TRANSFERRED, fromId + " -> " + to.getBedId()
                        + " (" + from.getType().getDisplayName() + " -> " + to.getType().getDisplayName() + ")");
                return null;
            });
        } catch (RuntimeException e) {
            beds.load();
            p.moveToBed(fromId);
            return OperationResult.fail("Transfer failed and was rolled back: " + e.getMessage());
        }
        checkCapacity(to.getType());
        fireChanged();
        return OperationResult.ok("Transferred " + p.getFullName() + " from " + fromId + " to " + to.getBedId()
                + ". " + fromId + " is being cleaned.", p);
    }

    public OperationResult dischargePatient(int patientId) {
        if (!hasPermission(Permission.DISCHARGE_PATIENT)) return denied(Permission.DISCHARGE_PATIENT);
        Patient patient = patients.findById(patientId);
        if (patient == null || patient.getStatus() != PatientStatus.ADMITTED) {
            return OperationResult.fail("Patient is not currently admitted.");
        }
        String bedId = patient.getAssignedBedId();
        Integer doctorId = patient.getAssignedDoctorId();
        try {
            db.inTransaction(conn -> {
                beds.release(bedId);
                doctors.release(doctorId);
                patient.markDischarged(clock.now());
                patients.update(patient);
                patients.log(patient, Action.DISCHARGED, "Bed " + bedId + " sent for cleaning");
                return null;
            });
        } catch (RuntimeException e) {
            // reload from the database to undo partial in-memory changes
            beds.load();
            doctors.load();
            patient.restoreDisposition(PatientStatus.ADMITTED, bedId, doctorId, patient.getAdmittedTime(), null);
            return OperationResult.fail("Discharge failed: " + e.getMessage());
        }
        alerts.clearOnce("full-" + beds.findById(bedId).getType());
        alerts.raise(AlertSeverity.INFO, Alert.Category.DISCHARGE,
                patient.getFullName() + " discharged from " + bedId + "; bed awaiting cleaning", patient.getId());
        fireChanged();
        return OperationResult.ok("Discharged " + patient.getFullName() + ". " + bedId + " is now being cleaned.", patient);
    }

    public OperationResult markLeftWithoutBeingSeen(int patientId) {
        if (!hasPermission(Permission.ADMIT_PATIENT)) return denied(Permission.ADMIT_PATIENT);
        if (!triage.isWaiting(patientId)) return OperationResult.fail("Patient is not waiting.");
        Patient patient = patients.findById(patientId);
        try {
            db.inTransaction(conn -> {
                patient.markLeftWithoutBeingSeen(clock.now());
                patients.update(patient);
                patients.log(patient, Action.LEFT_WITHOUT_BEING_SEEN, "Waited " + patient.waitingMinutes(clock.now()) + " min");
                return null;
            });
        } catch (RuntimeException e) {
            patient.restoreDisposition(PatientStatus.WAITING, null, null, null, null);
            return OperationResult.fail("Could not update patient: " + e.getMessage());
        }
        triage.remove(patientId);
        alerts.raise(AlertSeverity.WARNING, Alert.Category.WAIT_TIME_EXCEEDED,
                patient.getFullName() + " left without being seen", patientId);
        fireChanged();
        return OperationResult.ok(patient.getFullName() + " removed from the queue (left without being seen).");
    }

    public OperationResult markBedClean(String bedId) {
        if (!hasPermission(Permission.MANAGE_BEDS)) return denied(Permission.MANAGE_BEDS);
        try {
            beds.markClean(bedId);
        } catch (RuntimeException e) {
            return OperationResult.fail(e.getMessage());
        }
        alerts.clearOnce("full-" + beds.findById(bedId).getType());
        alerts.clearOnce("occupancy-high");
        fireChanged();
        return OperationResult.ok(bedId + " is clean and available.");
    }

    public OperationResult toggleBedMaintenance(String bedId) {
        if (!hasPermission(Permission.MANAGE_BEDS)) return denied(Permission.MANAGE_BEDS);
        try {
            BedStatus status = beds.toggleMaintenance(bedId);
            fireChanged();
            return OperationResult.ok(bedId + " is now " + status + ".");
        } catch (RuntimeException e) {
            return OperationResult.fail(e.getMessage());
        }
    }

    public OperationResult toggleDoctorDuty(int doctorId) {
        if (!hasPermission(Permission.MANAGE_DOCTORS)) return denied(Permission.MANAGE_DOCTORS);
        try {
            boolean onDuty = doctors.toggleDuty(doctorId);
            fireChanged();
            return OperationResult.ok(doctors.findById(doctorId).getName() + (onDuty ? " is on duty." : " is off duty."));
        } catch (RuntimeException e) {
            return OperationResult.fail(e.getMessage());
        }
    }

    // =====================================================================
    // Prediction
    // =====================================================================

    /** Predicted minutes until each waiting patient gets a bed, keyed by patient id. */
    public Map<Integer, WaitTimePredictor.Prediction> predictWaits() {
        WaitTimePredictor predictor = new WaitTimePredictor(beds.matcher(),
                WaitTimePredictor.defaultLengthOfStay(), settings.get().cleaningMinutes());
        return predictor.predict(triage.waitingInPriorityOrder(), beds.allBeds(), patients::findById, clock.now());
    }

    // =====================================================================
    // Periodic processing
    // =====================================================================

    /** Called every few seconds by the UI: aging, optional auto-admit, overdue and capacity checks. */
    public void tick() {
        triage.reprioritizeAll();
        if (settings.get().autoAdmit() && triage.waitingCount() > 0 && beds.availableCount() > 0) {
            autoAllocateAs(AUTO_ADMIT_ACTOR);
        }
        LocalDateTime now = clock.now();
        for (Patient p : triage.waitingInPriorityOrder()) {
            if (p.isOverdue(now)) {
                alerts.raiseOnce("overdue-" + p.getId(),
                        p.getTriageLevel().isCritical() ? AlertSeverity.CRITICAL : AlertSeverity.WARNING,
                        Alert.Category.WAIT_TIME_EXCEEDED,
                        String.format("%s (%s) has waited %d min - target is %d min",
                                p.getFullName(), p.getTriageLevel(), p.waitingMinutes(now),
                                p.getTriageLevel().getTargetWaitMinutes()),
                        p.getId());
            }
        }
        fireChanged();
    }

    public OperationResult advanceClock(int minutes) {
        if (!hasPermission(Permission.SIMULATE)) return denied(Permission.SIMULATE);
        if (minutes <= 0 || minutes > 24 * 60) return OperationResult.fail("Advance by 1 to 1440 minutes.");
        clock.advance(Duration.ofMinutes(minutes));
        tick();
        return OperationResult.ok("Simulation clock advanced " + minutes + " minutes - wait-time aging applied.");
    }

    private void checkCapacity(BedType type) {
        if (beds.availableCount(type) == 0) {
            alerts.raiseOnce("full-" + type, AlertSeverity.WARNING, Alert.Category.BED_CAPACITY,
                    type.getDisplayName() + " beds are at full capacity", null);
        }
        if (beds.occupancyRate() >= settings.get().highOccupancyThreshold()) {
            alerts.raiseOnce("occupancy-high", AlertSeverity.CRITICAL, Alert.Category.BED_CAPACITY,
                    String.format("Hospital occupancy at %.0f%% - consider diverting ambulances", beds.occupancyRate() * 100), null);
        }
    }

    /** Clears all patients/alerts and frees every bed. Beds, doctors, staff and settings are kept. */
    public OperationResult resetDemoData() {
        if (!hasPermission(Permission.RESET_DATA)) return denied(Permission.RESET_DATA);
        new SchemaInitializer(db).resetActivity();
        clock.reset();
        triage.clear();
        patients.clear();
        alerts.clear();
        beds.load();
        doctors.load();
        alerts.raise(AlertSeverity.INFO, Alert.Category.SYSTEM, "Demo data reset by " + auth.actorName(), null);
        fireChanged();
        return OperationResult.ok("All patient activity cleared.");
    }

    // =====================================================================
    // Queries for the UI
    // =====================================================================

    public List<Patient> waitingQueue() { return triage.waitingInPriorityOrder(); }
    public List<String> triageReasons(int patientId) { return triage.reasonsFor(patientId); }
    public List<Patient> admittedPatients() { return patients.admitted(); }
    public List<Patient> recentlyDischarged(int limit) { return patients.recentlyClosed(limit); }
    public List<LogEntry> recentActivity(int limit) { return patients.recentActivity(limit); }
    /** Active (waiting or admitted) patient, or null. */
    public Patient findPatient(int id) { return patients.findById(id); }
    /** Any patient ever registered, including discharged ones. */
    public Optional<Patient> findPatientRecord(int id) { return patients.findAnywhere(id); }
    /** Case-insensitive search by name or MRN over all patients, newest first. */
    public List<Patient> searchPatients(String text, int limit) { return patients.search(text, limit); }
    /** Every patient ever registered (for reports and exports). */
    public List<Patient> allPatientRecords() { return patients.allPatients(); }
    /** Audit trail of one patient, oldest first. */
    public List<LogEntry> patientTimeline(int patientId) { return patients.timeline(patientId); }
    public List<Bed> allBeds() { return beds.allBeds(); }
    public Bed findBed(String bedId) { return beds.findById(bedId); }
    public BedType idealBedType(Patient p) { return beds.idealBedType(p); }
    public List<Doctor> allDoctors() { return doctors.allDoctors(); }
    public Doctor findDoctor(Integer id) { return doctors.findById(id); }
    public List<Alert> recentAlerts() { return alerts.recent(); }
    public long unacknowledgedAlertCount() { return alerts.unacknowledgedCount(); }

    public void acknowledgeAlert(int id) {
        if (auth.currentUser() == null) return;
        alerts.acknowledge(id);
        fireChanged();
    }

    public void acknowledgeAllAlerts() {
        if (auth.currentUser() == null) return;
        alerts.acknowledgeAll();
        fireChanged();
    }

    public AnalyticsSnapshot analytics() { return analytics.snapshot(); }
    public LocalDateTime now() { return clock.now(); }
    public Duration clockOffset() { return clock.getOffset(); }
    public String databaseDescription() { return db.description(); }

    public void addAlertListener(Consumer<Alert> listener) { alerts.addListener(listener); }

    public void addChangeListener(Runnable listener) { changeListeners.add(listener); }

    private OperationResult changed(OperationResult r) {
        if (r.success()) fireChanged();
        return r;
    }

    private void fireChanged() {
        for (Runnable r : List.copyOf(changeListeners)) r.run();
    }

    @Override
    public void close() {
        db.close();
    }
}
