package com.hospital.model;

import java.time.LocalDateTime;

/** A notification raised by the backend and shown on the Alerts screen. */
public class Alert {

    public enum Category {
        CRITICAL_ARRIVAL,
        DETERIORATION,
        WAIT_TIME_EXCEEDED,
        BED_CAPACITY,
        NO_DOCTOR_AVAILABLE,
        ALLOCATION,
        DISCHARGE,
        SYSTEM
    }

    private int id;
    private final AlertSeverity severity;
    private final Category category;
    private final String message;
    private final Integer patientId;
    private final LocalDateTime createdAt;
    private boolean acknowledged;

    public Alert(int id, AlertSeverity severity, Category category, String message,
                 Integer patientId, LocalDateTime createdAt, boolean acknowledged) {
        this.id = id;
        this.severity = severity;
        this.category = category;
        this.message = message;
        this.patientId = patientId;
        this.createdAt = createdAt;
        this.acknowledged = acknowledged;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public AlertSeverity getSeverity() { return severity; }
    public Category getCategory() { return category; }
    public String getMessage() { return message; }
    public Integer getPatientId() { return patientId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isAcknowledged() { return acknowledged; }
    public void acknowledge() { this.acknowledged = true; }

    @Override
    public String toString() {
        return "[" + severity + "] " + message;
    }
}
