package com.hospital.service;

import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.model.TriageLevel;
import com.hospital.service.AnalyticsSnapshot.DoctorLoad;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Aggregates live statistics from the other services. */
public class AnalyticsService {

    private final TriageService triage;
    private final BedAllocationService beds;
    private final DoctorDispatchService doctors;
    private final PatientManagementService patients;
    private final HospitalClock clock;

    public AnalyticsService(TriageService triage, BedAllocationService beds, DoctorDispatchService doctors,
                            PatientManagementService patients, HospitalClock clock) {
        this.triage = triage;
        this.beds = beds;
        this.doctors = doctors;
        this.patients = patients;
        this.clock = clock;
    }

    public AnalyticsSnapshot snapshot() {
        LocalDateTime now = clock.now();
        List<Patient> waiting = triage.waitingInPriorityOrder();

        Map<TriageLevel, Integer> countByLevel = new EnumMap<>(TriageLevel.class);
        Map<TriageLevel, Long> totalWaitByLevel = new EnumMap<>(TriageLevel.class);
        for (TriageLevel level : TriageLevel.values()) {
            countByLevel.put(level, 0);
            totalWaitByLevel.put(level, 0L);
        }
        int critical = 0;
        int overdue = 0;
        long longest = 0;
        long totalWait = 0;
        for (Patient p : waiting) {
            long w = p.waitingMinutes(now);
            countByLevel.merge(p.getTriageLevel(), 1, Integer::sum);
            totalWaitByLevel.merge(p.getTriageLevel(), w, Long::sum);
            if (p.getTriageLevel().isCritical()) critical++;
            if (p.isOverdue(now)) overdue++;
            longest = Math.max(longest, w);
            totalWait += w;
        }
        Map<TriageLevel, Double> avgByLevel = new EnumMap<>(TriageLevel.class);
        for (TriageLevel level : TriageLevel.values()) {
            int n = countByLevel.get(level);
            avgByLevel.put(level, n == 0 ? 0.0 : (double) totalWaitByLevel.get(level) / n);
        }

        Map<BedStatus, Integer> bedsByStatus = new EnumMap<>(BedStatus.class);
        for (BedStatus s : BedStatus.values()) bedsByStatus.put(s, beds.count(s));
        Map<BedType, int[]> byType = new EnumMap<>(BedType.class);
        for (BedType t : BedType.values()) {
            byType.put(t, new int[]{beds.count(t, BedStatus.OCCUPIED), beds.totalCount(t)});
        }

        List<DoctorLoad> loads = new ArrayList<>();
        for (Doctor d : doctors.allDoctors()) {
            loads.add(new DoctorLoad(d.getName(), d.getSpecialty().getDisplayName(),
                    d.getActivePatients(), d.getMaxPatients(), d.isOnDuty()));
        }
        loads.sort(Comparator.comparingDouble((DoctorLoad l) -> (double) l.active() / l.max()).reversed());

        List<Patient> admitted = patients.admitted();
        double doorToBed = admitted.isEmpty() ? 0
                : admitted.stream().mapToLong(p -> p.waitingMinutes(now)).average().orElse(0);

        return new AnalyticsSnapshot(
                waiting.size(), critical, overdue, longest,
                waiting.isEmpty() ? 0 : (double) totalWait / waiting.size(),
                Collections.unmodifiableMap(countByLevel),
                Collections.unmodifiableMap(avgByLevel),
                patients.countsByStatus(),
                beds.totalCount(), beds.availableCount(), beds.occupancyRate(),
                Collections.unmodifiableMap(bedsByStatus),
                Collections.unmodifiableMap(byType),
                doctors.onDutyCount(), doctors.availableCount(),
                Collections.unmodifiableList(loads),
                doorToBed);
    }
}
