package com.hospital.model;

/** Lifecycle of a patient inside the emergency department. */
public enum PatientStatus {
    WAITING,
    ADMITTED,
    DISCHARGED,
    LEFT_WITHOUT_BEING_SEEN;

    public boolean isActive() {
        return this == WAITING || this == ADMITTED;
    }
}
