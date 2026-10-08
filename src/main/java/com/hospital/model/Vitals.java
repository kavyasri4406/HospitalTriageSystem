package com.hospital.model;

/**
 * Immutable snapshot of a patient's vital signs taken at triage.
 *
 * @param heartRate       beats per minute
 * @param systolicBp      mmHg
 * @param diastolicBp     mmHg
 * @param respiratoryRate breaths per minute
 * @param spo2            oxygen saturation in %
 * @param temperature     body temperature in degrees Celsius
 * @param painScore       0 (none) to 10 (worst)
 * @param gcs             Glasgow Coma Scale, 3 (unresponsive) to 15 (alert)
 */
public record Vitals(int heartRate, int systolicBp, int diastolicBp, int respiratoryRate,
                     int spo2, double temperature, int painScore, int gcs) {

    public String bloodPressure() {
        return systolicBp + "/" + diastolicBp;
    }

    @Override
    public String toString() {
        return String.format("HR %d | BP %s | RR %d | SpO2 %d%% | T %.1f°C | Pain %d | GCS %d",
                heartRate, bloodPressure(), respiratoryRate, spo2, temperature, painScore, gcs);
    }
}
