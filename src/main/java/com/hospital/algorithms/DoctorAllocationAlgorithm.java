package com.hospital.algorithms;

import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.model.Specialty;
import com.hospital.model.TriageLevel;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Chooses the doctor for a newly admitted patient.
 *
 * <p>Every on-duty doctor with spare capacity gets a cost:
 * <pre>
 *   cost = specialtyPenalty * 10 + loadRatio * 5
 *   specialtyPenalty: exact specialty = 0, emergency physician (generalist) = 1, anyone else = 2
 * </pre>
 * Candidates go into a {@link PriorityQueue} (min-heap on cost) and the cheapest is taken,
 * which balances "right expertise" against "least busy".
 */
public class DoctorAllocationAlgorithm {

    public record Candidate(Doctor doctor, double cost) {}

    public Specialty requiredSpecialty(Patient patient) {
        String complaint = patient.getChiefComplaint().toLowerCase(Locale.ROOT);
        if (patient.isTrauma()) return Specialty.TRAUMA_SURGERY;
        if (patient.getTriageLevel() == TriageLevel.RESUSCITATION) return Specialty.CRITICAL_CARE;
        if (complaint.contains("chest") || complaint.contains("heart") || complaint.contains("palpitation")
                || complaint.contains("cardiac")) {
            return Specialty.CARDIOLOGY;
        }
        if (patient.getAge() <= 15) return Specialty.PEDIATRICS;
        if (patient.getTriageLevel() != null && patient.getTriageLevel().getEsi() >= 4) {
            return Specialty.EMERGENCY_MEDICINE;
        }
        return Specialty.INTERNAL_MEDICINE;
    }

    public Doctor selectDoctor(Patient patient, Collection<Doctor> doctors) {
        Specialty needed = requiredSpecialty(patient);
        PriorityQueue<Candidate> candidates =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::cost)
                        .thenComparing(c -> c.doctor().getId()));
        for (Doctor doctor : doctors) {
            if (!doctor.canAcceptPatient()) continue;
            candidates.offer(new Candidate(doctor, cost(doctor, needed)));
        }
        Candidate best = candidates.poll();
        return best == null ? null : best.doctor();
    }

    double cost(Doctor doctor, Specialty needed) {
        int penalty;
        if (doctor.getSpecialty() == needed) penalty = 0;
        else if (doctor.getSpecialty() == Specialty.EMERGENCY_MEDICINE) penalty = 1;
        else penalty = 2;
        return penalty * 10 + doctor.loadRatio() * 5;
    }
}
