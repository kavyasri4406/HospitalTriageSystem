package com.hospital.model;

/**
 * Raw data captured by the intake form before validation.
 * Kept separate from {@link Patient} so invalid input never becomes a domain object.
 */
public record PatientIntakeForm(
        String fullName,
        int age,
        String gender,
        String chiefComplaint,
        boolean trauma,
        int heartRate,
        int systolicBp,
        int diastolicBp,
        int respiratoryRate,
        int spo2,
        double temperature,
        int painScore,
        int gcs,
        int expectedResources) {

    public Vitals toVitals() {
        return new Vitals(heartRate, systolicBp, diastolicBp, respiratoryRate, spo2, temperature, painScore, gcs);
    }
}
