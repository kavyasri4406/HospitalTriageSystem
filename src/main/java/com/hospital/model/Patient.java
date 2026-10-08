package com.hospital.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Abstract base for every emergency patient.
 * Age-group subclasses ({@link AdultPatient}, {@link PediatricPatient},
 * {@link GeriatricPatient}) override the clinical thresholds that differ by age,
 * so the triage algorithm stays generic (polymorphism).
 */
public abstract class Patient {
    private int id;
    private String mrn;
    private final String fullName;
    private final int age;
    private final String gender;
    private final String chiefComplaint;
    private final boolean trauma;
    private Vitals vitals;
    private int expectedResources;
    private final LocalDateTime intakeTime;

    // Triage results (set by TriageService)
    private TriageLevel triageLevel;
    private double severityScore;
    private double agingBonus;
    private double priorityScore;

    // Disposition
    private PatientStatus status = PatientStatus.WAITING;
    private String assignedBedId;
    private Integer assignedDoctorId;
    private LocalDateTime admittedTime;
    private LocalDateTime dischargedTime;

    protected Patient(String fullName, int age, String gender, String chiefComplaint, boolean trauma,
                      Vitals vitals, int expectedResources, LocalDateTime intakeTime) {
        this.fullName = fullName;
        this.age = age;
        this.gender = gender;
        this.chiefComplaint = chiefComplaint;
        this.trauma = trauma;
        this.vitals = vitals;
        this.expectedResources = expectedResources;
        this.intakeTime = intakeTime;
    }

    /** Short label of the age group, persisted in the database. */
    public abstract String getCategory();

    /** Heart rate above which vitals are in the ESI "danger zone" for this age group. */
    public abstract int dangerZoneHeartRate();

    /** Respiratory rate above which vitals are in the ESI "danger zone" for this age group. */
    public abstract int dangerZoneRespiratoryRate();

    /** Multiplier applied to the severity score to reflect age-related risk. */
    public abstract double ageRiskModifier();

    /** The ward a non-critical patient of this age group should normally go to. */
    public abstract BedType standardBedType();

    public long waitingMinutes(LocalDateTime now) {
        LocalDateTime end = admittedTime != null ? admittedTime : now;
        return Math.max(0, Duration.between(intakeTime, end).toMinutes());
    }

    public boolean isOverdue(LocalDateTime now) {
        return status == PatientStatus.WAITING && triageLevel != null
                && waitingMinutes(now) > triageLevel.getTargetWaitMinutes();
    }

    public void markAdmitted(String bedId, Integer doctorId, LocalDateTime when) {
        this.assignedBedId = bedId;
        this.assignedDoctorId = doctorId;
        this.admittedTime = when;
        this.status = PatientStatus.ADMITTED;
    }

    /** New measurements from a re-assessment; the caller re-runs triage afterwards. */
    public void updateVitals(Vitals newVitals, int newExpectedResources) {
        this.vitals = newVitals;
        this.expectedResources = newExpectedResources;
    }

    /** Bed transfer while admitted. */
    public void moveToBed(String bedId) {
        if (status != PatientStatus.ADMITTED) {
            throw new IllegalStateException("Only admitted patients can be transferred");
        }
        this.assignedBedId = bedId;
    }

    public void markDischarged(LocalDateTime when) {
        this.dischargedTime = when;
        this.status = PatientStatus.DISCHARGED;
    }

    public void markLeftWithoutBeingSeen(LocalDateTime when) {
        this.dischargedTime = when;
        this.status = PatientStatus.LEFT_WITHOUT_BEING_SEEN;
    }

    public void applyTriage(TriageLevel level, double severity, double aging, double priority) {
        this.triageLevel = level;
        this.severityScore = severity;
        this.agingBonus = aging;
        this.priorityScore = priority;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getMrn() { return mrn; }
    public void setMrn(String mrn) { this.mrn = mrn; }
    public String getFullName() { return fullName; }
    public int getAge() { return age; }
    public String getGender() { return gender; }
    public String getChiefComplaint() { return chiefComplaint; }
    public boolean isTrauma() { return trauma; }
    public Vitals getVitals() { return vitals; }
    public int getExpectedResources() { return expectedResources; }
    public LocalDateTime getIntakeTime() { return intakeTime; }
    public TriageLevel getTriageLevel() { return triageLevel; }
    public double getSeverityScore() { return severityScore; }
    public double getAgingBonus() { return agingBonus; }
    public double getPriorityScore() { return priorityScore; }
    public PatientStatus getStatus() { return status; }
    public void setStatus(PatientStatus status) { this.status = status; }
    public String getAssignedBedId() { return assignedBedId; }
    public Integer getAssignedDoctorId() { return assignedDoctorId; }
    public LocalDateTime getAdmittedTime() { return admittedTime; }
    public LocalDateTime getDischargedTime() { return dischargedTime; }

    /** Used when rehydrating a patient from the database. */
    public void restoreDisposition(PatientStatus status, String bedId, Integer doctorId,
                                   LocalDateTime admitted, LocalDateTime discharged) {
        this.status = status;
        this.assignedBedId = bedId;
        this.assignedDoctorId = doctorId;
        this.admittedTime = admitted;
        this.dischargedTime = discharged;
    }

    @Override
    public String toString() {
        return String.format("%s %s (%d%s) %s", mrn, fullName, age, gender.isEmpty() ? "" : gender.substring(0, 1),
                triageLevel == null ? "untriaged" : triageLevel.toString());
    }
}
