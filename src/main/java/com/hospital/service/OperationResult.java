package com.hospital.service;

import com.hospital.algorithms.TriageAssessment;
import com.hospital.model.Patient;

import java.util.List;

/**
 * Outcome of a user action, returned to the UI.
 *
 * @param success    whether the action went through
 * @param message    one-line summary for a status bar or dialog
 * @param patient    patient involved, if any
 * @param assessment triage result (for registrations)
 * @param errors     validation errors (for failed registrations)
 */
public record OperationResult(boolean success, String message, Patient patient,
                              TriageAssessment assessment, List<String> errors) {

    public static OperationResult ok(String message) {
        return new OperationResult(true, message, null, null, List.of());
    }

    public static OperationResult ok(String message, Patient patient) {
        return new OperationResult(true, message, patient, null, List.of());
    }

    public static OperationResult fail(String message) {
        return new OperationResult(false, message, null, null, List.of());
    }

    public static OperationResult invalid(List<String> errors) {
        return new OperationResult(false, "Please correct the highlighted fields.", null, null, errors);
    }
}
