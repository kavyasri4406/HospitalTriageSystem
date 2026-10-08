package com.hospital.service;

import com.hospital.algorithms.EsiScoringAlgorithm;
import com.hospital.algorithms.PatientPriorityComparator;
import com.hospital.algorithms.TriageAssessment;
import com.hospital.algorithms.WaitTimeAgingAlgorithm;
import com.hospital.datastructures.TriagePriorityQueue;
import com.hospital.model.Patient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores patients with the ESI algorithm and owns the waiting-room priority queue.
 * Priorities are refreshed periodically so waiting patients age upward.
 */
public class TriageService {

    private final EsiScoringAlgorithm esi;
    private final WaitTimeAgingAlgorithm aging;
    private final HospitalClock clock;
    private final TriagePriorityQueue<Integer, Patient> queue =
            new TriagePriorityQueue<>(new PatientPriorityComparator(), Patient::getId);
    private final Map<Integer, List<String>> reasons = new HashMap<>();

    public TriageService(EsiScoringAlgorithm esi, WaitTimeAgingAlgorithm aging, HospitalClock clock) {
        this.esi = esi;
        this.aging = aging;
        this.clock = clock;
    }

    /** Runs the ESI algorithm and stores level, severity and current priority on the patient. */
    public TriageAssessment assess(Patient patient) {
        TriageAssessment result = esi.assess(patient);
        long waited = patient.waitingMinutes(clock.now());
        patient.applyTriage(result.level(), result.severityScore(),
                aging.agingBonus(result.level(), waited),
                aging.priority(result.severityScore(), result.level(), waited));
        return result;
    }

    /** ESI result for a patient object that is not (yet) in the system. */
    public TriageAssessment preview(Patient patient) {
        return esi.assess(patient);
    }

    /**
     * Re-runs ESI after new vitals. For a waiting patient the heap position is repaired in
     * O(log n) with {@code update(key)} instead of rebuilding the whole queue.
     */
    public TriageAssessment reassess(Patient patient) {
        TriageAssessment result = assess(patient);
        if (queue.contains(patient.getId())) {
            queue.update(patient.getId());
            reasons.put(patient.getId(), result.reasons());
        }
        return result;
    }

    public void enqueue(Patient patient, List<String> why) {
        queue.offer(patient);
        reasons.put(patient.getId(), why);
    }

    /** Re-applies wait-time aging to every waiting patient, then re-heapifies in O(n). */
    public void reprioritizeAll() {
        LocalDateTime now = clock.now();
        for (Patient p : queue.elements()) {
            long waited = p.waitingMinutes(now);
            p.applyTriage(p.getTriageLevel(), p.getSeverityScore(),
                    aging.agingBonus(p.getTriageLevel(), waited),
                    aging.priority(p.getSeverityScore(), p.getTriageLevel(), waited));
        }
        queue.rebuild();
    }

    public Patient peekNext() { return queue.peek(); }

    public Patient pollNext() {
        Patient p = queue.poll();
        if (p != null) reasons.remove(p.getId());
        return p;
    }

    public Patient remove(int patientId) {
        reasons.remove(patientId);
        return queue.remove(patientId);
    }

    public boolean isWaiting(int patientId) { return queue.contains(patientId); }

    /** Waiting patients, highest priority first. */
    public List<Patient> waitingInPriorityOrder() { return queue.toSortedList(); }

    public List<String> reasonsFor(int patientId) {
        return reasons.getOrDefault(patientId, List.of());
    }

    public int waitingCount() { return queue.size(); }

    public void clear() {
        queue.clear();
        reasons.clear();
    }
}
