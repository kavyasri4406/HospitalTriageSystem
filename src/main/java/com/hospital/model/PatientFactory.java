package com.hospital.model;

import java.time.LocalDateTime;

/** Factory that picks the right {@link Patient} subclass from the patient's age. */
public final class PatientFactory {

    public static final int PEDIATRIC_MAX_AGE = 15;
    public static final int GERIATRIC_MIN_AGE = 65;

    private PatientFactory() {}

    public static Patient create(String fullName, int age, String gender, String chiefComplaint, boolean trauma,
                                 Vitals vitals, int expectedResources, LocalDateTime intakeTime) {
        if (age <= PEDIATRIC_MAX_AGE) {
            return new PediatricPatient(fullName, age, gender, chiefComplaint, trauma, vitals, expectedResources, intakeTime);
        }
        if (age >= GERIATRIC_MIN_AGE) {
            return new GeriatricPatient(fullName, age, gender, chiefComplaint, trauma, vitals, expectedResources, intakeTime);
        }
        return new AdultPatient(fullName, age, gender, chiefComplaint, trauma, vitals, expectedResources, intakeTime);
    }

    public static Patient create(PatientIntakeForm form, LocalDateTime intakeTime) {
        return create(form.fullName().trim(), form.age(), form.gender(), form.chiefComplaint().trim(), form.trauma(),
                form.toVitals(), form.expectedResources(), intakeTime);
    }
}
