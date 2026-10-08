package com.hospital.algorithms;

import com.hospital.datastructures.BedRegistry;
import com.hospital.model.Bed;
import com.hospital.model.BedType;
import com.hospital.model.Patient;
import com.hospital.model.TriageLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy bed matcher.
 *
 * <ol>
 *   <li>Derive the ideal bed type from ESI level, trauma flag and age group.</li>
 *   <li>Walk a fallback chain for that type, nearest clinical equivalent first.</li>
 *   <li>Within a type, pick the bed that has been free the longest (spreads wear and cleaning).</li>
 * </ol>
 * Scarce ICU/Trauma beds are never given to ESI 4-5 patients.
 */
public class BedMatchingAlgorithm {

    /** Result of a match; {@code bed} is null when nothing suitable is free. */
    public record BedMatch(Bed bed, BedType requestedType, boolean fallback, String explanation) {
        public boolean found() { return bed != null; }
    }

    private static final Map<BedType, List<BedType>> FALLBACKS = new EnumMap<>(BedType.class);

    static {
        FALLBACKS.put(BedType.ICU, List.of(BedType.ICU, BedType.TRAUMA, BedType.MONITORED));
        FALLBACKS.put(BedType.TRAUMA, List.of(BedType.TRAUMA, BedType.ICU, BedType.MONITORED));
        FALLBACKS.put(BedType.MONITORED, List.of(BedType.MONITORED, BedType.ICU, BedType.GENERAL));
        FALLBACKS.put(BedType.PEDIATRIC, List.of(BedType.PEDIATRIC, BedType.MONITORED, BedType.GENERAL));
        FALLBACKS.put(BedType.GENERAL, List.of(BedType.GENERAL, BedType.MONITORED, BedType.PEDIATRIC));
    }

    public BedType idealBedType(Patient patient) {
        TriageLevel level = patient.getTriageLevel();
        if (level == TriageLevel.RESUSCITATION) {
            return patient.isTrauma() ? BedType.TRAUMA : BedType.ICU;
        }
        if (level == TriageLevel.EMERGENT) {
            return patient.isTrauma() ? BedType.TRAUMA : BedType.MONITORED;
        }
        return patient.standardBedType();
    }

    /**
     * Bed types this patient may be placed in, best first: the ideal type followed by its
     * fallbacks, minus types the patient must never get (ICU/Trauma for ESI 4-5, Pediatric for adults).
     */
    public List<BedType> candidateBedTypes(Patient patient) {
        BedType ideal = idealBedType(patient);
        boolean lowAcuity = patient.getTriageLevel().getEsi() >= 4;
        List<BedType> result = new ArrayList<>();
        for (BedType candidateType : FALLBACKS.get(ideal)) {
            if (lowAcuity && (candidateType == BedType.ICU || candidateType == BedType.TRAUMA)) {
                continue; // protect critical-care capacity
            }
            if (candidateType == BedType.PEDIATRIC && patient.getAge() > 15) {
                continue; // adults never go to the pediatric ward
            }
            result.add(candidateType);
        }
        return result;
    }

    public BedMatch findBestBed(Patient patient, BedRegistry beds) {
        BedType ideal = idealBedType(patient);
        for (BedType candidateType : candidateBedTypes(patient)) {
            Bed bed = beds.availableOfType(candidateType).stream()
                    .min(Comparator.comparing(Bed::getUpdatedAt).thenComparing(Bed::getBedId))
                    .orElse(null);
            if (bed != null) {
                boolean fallback = candidateType != ideal;
                String why = fallback
                        ? "No free " + ideal.getDisplayName() + " bed; using " + candidateType.getDisplayName()
                        : "Matched " + ideal.getDisplayName() + " bed";
                return new BedMatch(bed, ideal, fallback, why);
            }
        }
        return new BedMatch(null, ideal, false, "No suitable bed free for " + ideal.getDisplayName());
    }
}
