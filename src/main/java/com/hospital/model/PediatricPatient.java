package com.hospital.model;

import java.time.LocalDateTime;

/** Patients under 16. Children have higher normal heart and breathing rates. */
public class PediatricPatient extends Patient {

    public PediatricPatient(String fullName, int age, String gender, String chiefComplaint, boolean trauma,
                            Vitals vitals, int expectedResources, LocalDateTime intakeTime) {
        super(fullName, age, gender, chiefComplaint, trauma, vitals, expectedResources, intakeTime);
    }

    @Override public String getCategory() { return "PEDIATRIC"; }

    @Override
    public int dangerZoneHeartRate() {
        // ESI v4 pediatric danger-zone thresholds
        if (getAge() < 3) return 160;
        if (getAge() <= 8) return 140;
        return 100;
    }

    @Override
    public int dangerZoneRespiratoryRate() {
        if (getAge() < 3) return 40;
        if (getAge() <= 8) return 30;
        return 20;
    }

    @Override
    public double ageRiskModifier() {
        return getAge() < 2 ? 1.10 : 1.05;
    }

    @Override public BedType standardBedType() { return BedType.PEDIATRIC; }
}
