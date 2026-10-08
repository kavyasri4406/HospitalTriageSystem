package com.hospital.model;

import java.time.LocalDateTime;

/** Patients aged 65+. Older patients decompensate faster, so risk is weighted up. */
public class GeriatricPatient extends Patient {

    public GeriatricPatient(String fullName, int age, String gender, String chiefComplaint, boolean trauma,
                            Vitals vitals, int expectedResources, LocalDateTime intakeTime) {
        super(fullName, age, gender, chiefComplaint, trauma, vitals, expectedResources, intakeTime);
    }

    @Override public String getCategory() { return "GERIATRIC"; }
    @Override public int dangerZoneHeartRate() { return 100; }
    @Override public int dangerZoneRespiratoryRate() { return 20; }
    @Override public double ageRiskModifier() { return getAge() >= 80 ? 1.20 : 1.15; }
    @Override public BedType standardBedType() { return BedType.GENERAL; }
}
