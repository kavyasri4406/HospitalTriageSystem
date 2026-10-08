package com.hospital.service;

import com.hospital.datastructures.PatientRegistry;
import com.hospital.model.Patient;
import com.hospital.model.PatientIntakeForm;
import com.hospital.model.PatientFactory;
import com.hospital.model.PatientStatus;
import com.hospital.model.VitalsRecord;
import com.hospital.persistence.dao.AdmissionLogDAO;
import com.hospital.persistence.dao.AdmissionLogDAO.Action;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.persistence.dao.PatientDAO;
import com.hospital.persistence.dao.VitalsHistoryDAO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Patient records: creation, MRN generation, lookups, status changes, vitals history and the audit log. */
public class PatientManagementService {

    private static final DateTimeFormatter MRN_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final PatientRegistry registry = new PatientRegistry();
    private final PatientDAO patientDAO;
    private final AdmissionLogDAO logDAO;
    private final VitalsHistoryDAO vitalsDAO;
    private final HospitalClock clock;
    private final Supplier<String> actor;

    /**
     * @param actor supplies the username recorded in audit entries (current user or "system")
     */
    public PatientManagementService(PatientDAO patientDAO, AdmissionLogDAO logDAO, VitalsHistoryDAO vitalsDAO,
                                    HospitalClock clock, Supplier<String> actor) {
        this.patientDAO = patientDAO;
        this.logDAO = logDAO;
        this.vitalsDAO = vitalsDAO;
        this.clock = clock;
        this.actor = actor;
    }

    /** Loads all waiting/admitted patients from the database into memory. */
    public List<Patient> loadActive() {
        registry.clear();
        List<Patient> active = patientDAO.findActive();
        active.forEach(registry::add);
        return active;
    }

    /** Builds a domain patient (correct age-group subclass) from validated intake data. */
    public Patient create(PatientIntakeForm form, LocalDateTime intakeTime) {
        Patient patient = PatientFactory.create(form, intakeTime);
        patient.setMrn(generateMrn());
        return patient;
    }

    /** Inserts a triaged patient into the database (assigns its id). */
    public void save(Patient patient) {
        patientDAO.insert(patient);
    }

    /** Adds a saved patient to the in-memory registry once its transaction has committed. */
    public void track(Patient patient) {
        registry.add(patient);
    }

    public void update(Patient patient) {
        patientDAO.update(patient);
    }

    public void log(Patient patient, Action action, String details) {
        log(patient, action, details, actor.get());
    }

    public void log(Patient patient, Action action, String details, String performedBy) {
        logDAO.log(patient.getId(), patient.getAssignedBedId(), patient.getAssignedDoctorId(),
                action, details, patient.getPriorityScore(), performedBy, clock.now());
    }

    /** Stores the patient's current vitals and triage result in the vitals history. */
    public void recordVitals(Patient patient) {
        vitalsDAO.insert(patient.getId(), patient.getVitals(), patient.getTriageLevel(),
                patient.getSeverityScore(), actor.get(), clock.now());
    }

    public List<VitalsRecord> vitalsHistory(int patientId) {
        return vitalsDAO.findByPatient(patientId);
    }

    public List<LogEntry> timeline(int patientId) {
        return logDAO.findByPatient(patientId);
    }

    /** Active patient from memory. */
    public Patient findById(int id) {
        return registry.findById(id);
    }

    /** Any patient, active or historical: memory first (live object), then the database. */
    public Optional<Patient> findAnywhere(int id) {
        Patient live = registry.findById(id);
        return live != null ? Optional.of(live) : patientDAO.findById(id);
    }

    /** Search all patients ever registered; active ones are returned as their live objects. */
    public List<Patient> search(String text, int limit) {
        return preferLive(patientDAO.search(text, limit));
    }

    public List<Patient> allPatients() {
        return preferLive(patientDAO.findAll());
    }

    public List<Patient> admitted() {
        return registry.withStatus(PatientStatus.ADMITTED);
    }

    public List<Patient> recentlyClosed(int limit) {
        return patientDAO.findRecentlyClosed(limit);
    }

    public Map<PatientStatus, Integer> countsByStatus() {
        return patientDAO.countByStatus();
    }

    public List<LogEntry> recentActivity(int limit) {
        return logDAO.recent(limit);
    }

    public void clear() {
        registry.clear();
    }

    private List<Patient> preferLive(List<Patient> fromDb) {
        List<Patient> result = new ArrayList<>(fromDb.size());
        for (Patient p : fromDb) {
            Patient live = registry.findById(p.getId());
            result.add(live != null ? live : p);
        }
        return result;
    }

    private String generateMrn() {
        String candidate;
        do {
            candidate = "MRN-" + clock.now().format(MRN_DATE) + "-"
                    + ThreadLocalRandom.current().nextInt(10000, 100000);
        } while (registry.findByMrn(candidate) != null);
        return candidate;
    }
}
