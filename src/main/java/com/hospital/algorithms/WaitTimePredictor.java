package com.hospital.algorithms;

import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Patient;
import com.hospital.model.TriageLevel;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Predicts how long each waiting patient will wait for a bed (discrete-event simulation).
 *
 * <ol>
 *   <li>Every usable bed gets the time it next becomes free: available = now, cleaning = when cleaning
 *       ends, occupied = occupant's admission + expected length of stay + cleaning. Beds in maintenance
 *       never free up and are left out.</li>
 *   <li>Beds go into one {@link PriorityQueue} per bed type (min-heap on free time, then bed id).</li>
 *   <li>Waiting patients are replayed in priority order, greedily, like {@code autoAllocate()}: each
 *       takes the earliest-free bed among its candidate types (the ideal type wins ties), and that bed
 *       is pushed back, free again after this patient's stay plus cleaning.</li>
 * </ol>
 * <pre>
 *   wait = max(0, bedFreeTime - now), partial minutes rounded up
 * </pre>
 * An occupant who has overstayed is assumed to leave imminently. Runs in O(P log B) for P waiting
 * patients and B beds, and never mutates its inputs.
 */
public class WaitTimePredictor {

    /**
     * @param patientId        waiting patient
     * @param minutesUntilBed  predicted minutes from {@code now} until a bed is free for them (0 = a bed is free now)
     * @param bedType          bed type the patient is predicted to get, null when {@code noBedPossible}
     * @param noBedPossible    true when no bed the patient may use can ever become free (e.g. all in maintenance)
     */
    public record Prediction(int patientId, long minutesUntilBed, BedType bedType, boolean noBedPossible) {}

    /** Default expected time a patient of each ESI level occupies an emergency bed. */
    public static Map<TriageLevel, Integer> defaultLengthOfStay() {
        Map<TriageLevel, Integer> los = new EnumMap<>(TriageLevel.class);
        los.put(TriageLevel.RESUSCITATION, 240);
        los.put(TriageLevel.EMERGENT, 180);
        los.put(TriageLevel.URGENT, 120);
        los.put(TriageLevel.LESS_URGENT, 60);
        los.put(TriageLevel.NON_URGENT, 45);
        return los;
    }

    /** A bed inside the simulation and the time it is next free. Immutable: re-queued after each use. */
    private record SimBed(String bedId, BedType type, LocalDateTime freeAt) {}

    private static final Comparator<SimBed> EARLIEST_FREE =
            Comparator.comparing(SimBed::freeAt).thenComparing(SimBed::bedId);

    private final BedMatchingAlgorithm matcher;
    private final Map<TriageLevel, Integer> lengthOfStayMinutes;
    private final int cleaningMinutes;

    /**
     * @param lengthOfStayMinutes expected stay per ESI level; missing (or null) entries use
     *                            {@link #defaultLengthOfStay()}. The map is copied.
     * @param cleaningMinutes     turnaround time between two patients in the same bed
     */
    public WaitTimePredictor(BedMatchingAlgorithm matcher, Map<TriageLevel, Integer> lengthOfStayMinutes, int cleaningMinutes) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        Map<TriageLevel, Integer> los = defaultLengthOfStay();
        if (lengthOfStayMinutes != null) {
            lengthOfStayMinutes.forEach((level, minutes) -> {
                if (level != null && minutes != null) los.put(level, Math.max(0, minutes));
            });
        }
        this.lengthOfStayMinutes = los;
        this.cleaningMinutes = Math.max(0, cleaningMinutes);
    }

    /**
     * @param waitingInPriorityOrder waiting patients, highest priority first
     * @param beds                   every bed (any status)
     * @param occupantLookup         patient id -> patient, for the occupants of OCCUPIED beds (may return null)
     * @param now                    current (simulation) time
     * @return prediction per waiting patient id, in queue order (unmodifiable); untriaged patients are left out
     */
    public Map<Integer, Prediction> predict(List<Patient> waitingInPriorityOrder, List<Bed> beds,
                                            Function<Integer, Patient> occupantLookup, LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        Map<BedType, PriorityQueue<SimBed>> freeBeds = new EnumMap<>(BedType.class);
        for (BedType type : BedType.values()) {
            freeBeds.put(type, new PriorityQueue<>(EARLIEST_FREE));
        }
        if (beds != null) {
            for (Bed bed : beds) {
                if (bed == null || bed.getType() == null) continue;
                LocalDateTime freeAt = nextFreeTime(bed, occupantLookup, now);
                if (freeAt != null) {
                    freeBeds.get(bed.getType()).offer(new SimBed(bed.getBedId(), bed.getType(), freeAt));
                }
            }
        }

        Map<Integer, Prediction> predictions = new LinkedHashMap<>();
        if (waitingInPriorityOrder == null) return Collections.unmodifiableMap(predictions);
        for (Patient patient : waitingInPriorityOrder) {
            if (patient == null || patient.getTriageLevel() == null) continue; // nothing to match on yet
            SimBed chosen = null;
            for (BedType type : matcher.candidateBedTypes(patient)) {
                SimBed head = freeBeds.get(type).peek();
                // strictly earlier only, so on a tie the type listed first (the ideal one) wins
                if (head != null && (chosen == null || head.freeAt().isBefore(chosen.freeAt()))) {
                    chosen = head;
                }
            }
            if (chosen == null) {
                predictions.put(patient.getId(), new Prediction(patient.getId(), -1, null, true));
                continue;
            }
            PriorityQueue<SimBed> heap = freeBeds.get(chosen.type());
            heap.poll();
            LocalDateTime start = latest(chosen.freeAt(), now);
            predictions.put(patient.getId(),
                    new Prediction(patient.getId(), minutesBetween(now, start), chosen.type(), false));
            long busyFor = lengthOfStay(patient.getTriageLevel()) + (long) cleaningMinutes;
            heap.offer(new SimBed(chosen.bedId(), chosen.type(), start.plusMinutes(busyFor)));
        }
        return Collections.unmodifiableMap(predictions);
    }

    /** When the bed can next take a patient, or null if it never will within the simulation (maintenance). */
    private LocalDateTime nextFreeTime(Bed bed, Function<Integer, Patient> occupantLookup, LocalDateTime now) {
        BedStatus status = bed.getStatus();
        if (status == null) return null;
        return switch (status) {
            case AVAILABLE -> now;
            case CLEANING -> bed.getUpdatedAt() == null
                    ? now.plusMinutes(cleaningMinutes)
                    : latest(bed.getUpdatedAt().plusMinutes(cleaningMinutes), now);
            case OCCUPIED -> latest(expectedDischarge(bed, occupantLookup, now), now).plusMinutes(cleaningMinutes);
            case MAINTENANCE -> null;
        };
    }

    /** Admission time + expected stay; an unknown occupant is assumed to have just started an ESI-3 stay. */
    private LocalDateTime expectedDischarge(Bed bed, Function<Integer, Patient> occupantLookup, LocalDateTime now) {
        Patient occupant = bed.getPatientId() == null || occupantLookup == null
                ? null : occupantLookup.apply(bed.getPatientId());
        if (occupant == null || occupant.getAdmittedTime() == null || occupant.getTriageLevel() == null) {
            return now.plusMinutes(lengthOfStay(TriageLevel.URGENT));
        }
        return occupant.getAdmittedTime().plusMinutes(lengthOfStay(occupant.getTriageLevel()));
    }

    private int lengthOfStay(TriageLevel level) {
        return lengthOfStayMinutes.get(level == null ? TriageLevel.URGENT : level);
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    /** Whole minutes from {@code from} to {@code to}, partial minutes rounded up, never negative. */
    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        Duration gap = Duration.between(from, to);
        if (gap.isNegative() || gap.isZero()) return 0;
        long minutes = gap.toMinutes();
        return gap.equals(Duration.ofMinutes(minutes)) ? minutes : minutes + 1;
    }
}
