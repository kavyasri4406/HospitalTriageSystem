package com.hospital.model;

import java.time.LocalDateTime;

/**
 * One set of vital signs in a patient's history, with the triage result it produced.
 *
 * @param recordedBy username of the staff member who took the measurement
 */
public record VitalsRecord(int id, int patientId, Vitals vitals, TriageLevel level, double severityScore,
                           String recordedBy, LocalDateTime recordedAt) {
}
