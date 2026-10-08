package com.hospital.model;

import java.time.LocalDateTime;

/** Patients aged 16 to 64. */
public class AdultPatient extends Patient {

    public AdultPatient(String fullName, int age, String gender, String chiefComplaint, boolean trauma,
                        Vitals vitals, int expectedResources, LocalDateTime intakeTime) {
        super(fullName, age, gender, chiefComplaint, trauma, vitals, expectedResources, intakeTime);
    }

    @Override public String getCategory() { return "ADULT"; }
    @Override public int dangerZoneHeartRate() { return 100; }
    @Override public int dangerZoneRespiratoryRate() { return 20; }
    @Override public double ageRiskModifier() { return 1.0; }
    @Override public BedType standardBedType() { return BedType.GENERAL; }
}
