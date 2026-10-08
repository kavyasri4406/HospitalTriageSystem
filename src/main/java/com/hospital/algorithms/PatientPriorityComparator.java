package com.hospital.algorithms;

import com.hospital.model.Patient;

import java.util.Comparator;

/**
 * Ordering for the triage queue, where "greater" means "should be seen first":
 * <ol>
 *   <li>higher priority score (severity + wait aging)</li>
 *   <li>more urgent ESI level (lower number)</li>
 *   <li>earlier arrival (first come, first served)</li>
 *   <li>lower id, to make the order fully deterministic</li>
 * </ol>
 */
public class PatientPriorityComparator implements Comparator<Patient> {

    @Override
    public int compare(Patient a, Patient b) {
        int cmp = Double.compare(a.getPriorityScore(), b.getPriorityScore());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(b.getTriageLevel().getEsi(), a.getTriageLevel().getEsi());
        if (cmp != 0) return cmp;
        cmp = b.getIntakeTime().compareTo(a.getIntakeTime());
        if (cmp != 0) return cmp;
        return Integer.compare(b.getId(), a.getId());
    }
}
