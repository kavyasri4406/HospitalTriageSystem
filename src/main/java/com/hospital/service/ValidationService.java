package com.hospital.service;

import com.hospital.model.PatientIntakeForm;

import java.util.Set;
import java.util.regex.Pattern;

/** Validates intake data against physiologically plausible ranges before a patient is created. */
public class ValidationService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L} .'-]{2,100}");
    private static final Set<String> GENDERS = Set.of("Male", "Female", "Other");

    public ValidationResult validate(PatientIntakeForm f) {
        ValidationResult r = new ValidationResult();

        if (f.fullName() == null || f.fullName().isBlank()) {
            r.addError("Full name is required.");
        } else if (!NAME_PATTERN.matcher(f.fullName().trim()).matches()) {
            r.addError("Full name must be 2-100 letters (spaces, . ' - allowed).");
        }
        if (f.gender() == null || !GENDERS.contains(f.gender())) {
            r.addError("Gender must be Male, Female or Other.");
        }
        if (f.chiefComplaint() == null || f.chiefComplaint().isBlank()) {
            r.addError("Chief complaint is required.");
        } else if (f.chiefComplaint().length() > 255) {
            r.addError("Chief complaint must be at most 255 characters.");
        }

        range(r, "Age", f.age(), 0, 120, "years");
        range(r, "Heart rate", f.heartRate(), 20, 250, "bpm");
        range(r, "Systolic BP", f.systolicBp(), 40, 260, "mmHg");
        range(r, "Diastolic BP", f.diastolicBp(), 20, 160, "mmHg");
        range(r, "Respiratory rate", f.respiratoryRate(), 4, 70, "/min");
        range(r, "SpO2", f.spo2(), 50, 100, "%");
        range(r, "Pain score", f.painScore(), 0, 10, "");
        range(r, "GCS", f.gcs(), 3, 15, "");
        range(r, "Expected resources", f.expectedResources(), 0, 5, "");
        if (Double.isNaN(f.temperature()) || f.temperature() < 30.0 || f.temperature() > 44.0) {
            r.addError("Temperature must be between 30.0 and 44.0 °C.");
        }
        if (f.diastolicBp() >= f.systolicBp()) {
            r.addError("Diastolic BP must be lower than systolic BP.");
        }
        return r;
    }

    private static void range(ValidationResult r, String field, int value, int min, int max, String unit) {
        if (value < min || value > max) {
            r.addError(String.format("%s must be between %d and %d %s.", field, min, max, unit).replace(" .", "."));
        }
    }
}
