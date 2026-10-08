package com.hospital.service;

import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.PatientStatus;
import com.hospital.model.TriageLevel;

import java.util.List;
import java.util.Map;

/** Immutable set of KPIs for the dashboard and analytics screens. */
public record AnalyticsSnapshot(
        int waitingCount,
        int criticalWaitingCount,
        int overdueCount,
        long longestWaitMinutes,
        double averageWaitMinutes,
        Map<TriageLevel, Integer> waitingByLevel,
        Map<TriageLevel, Double> averageWaitByLevel,
        Map<PatientStatus, Integer> patientsByStatus,
        int bedsTotal,
        int bedsAvailable,
        double occupancyRate,
        Map<BedStatus, Integer> bedsByStatus,
        Map<BedType, int[]> occupancyByType,
        int doctorsOnDuty,
        int doctorsAvailable,
        List<DoctorLoad> doctorLoads,
        double averageDoorToBedMinutes) {

    public record DoctorLoad(String name, String specialty, int active, int max, boolean onDuty) {}
}
