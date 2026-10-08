package com.hospital;

import com.hospital.algorithms.BedMatchingAlgorithm;
import com.hospital.algorithms.WaitTimePredictor;
import com.hospital.algorithms.WaitTimePredictor.Prediction;
import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Patient;
import com.hospital.model.PatientFactory;
import com.hospital.model.PatientStatus;
import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;
import com.hospital.service.HospitalManager;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hospital.SelfTestRunner.check;
import static com.hospital.SelfTestRunner.deleteQuietly;
import static com.hospital.SelfTestRunner.freshManager;
import static com.hospital.SelfTestRunner.run;
import static com.hospital.SelfTestRunner.tempDbFile;

/** Tests for {@link WaitTimePredictor}: the time-to-bed simulation for the waiting queue. */
final class PredictorTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 12, 0);
    private static final int CLEAN = 15;
    private static final Vitals VITALS = new Vitals(80, 120, 80, 16, 98, 37.0, 2, 15);

    private PredictorTests() {}

    static void runAll() {
        run("Predictor: empty queue gives no predictions", PredictorTests::emptyQueue);
        run("Predictor: a free bed means 0 minutes", PredictorTests::freeBed);
        run("Predictor: all beds occupied -> remaining stay + cleaning", PredictorTests::allOccupied);
        run("Predictor: two patients, one bed -> second waits first's stay + cleaning", PredictorTests::competing);
        run("Predictor: queue order decides who gets the bed first", PredictorTests::priorityOrder);
        run("Predictor: long queue cycles through the beds (list scheduling)", PredictorTests::spillOver);
        run("Predictor: only maintenance (or no) beds -> no bed possible", PredictorTests::maintenanceOnly);
        run("Predictor: ESI 4-5 never predicted into ICU/Trauma", PredictorTests::lowAcuityProtected);
        run("Predictor: adults never predicted into Pediatric beds", PredictorTests::adultsNotPediatric);
        run("Predictor: cleaning bed frees at updatedAt + cleaning (rounded up)", PredictorTests::cleaningBed);
        run("Predictor: overstaying occupant -> about the cleaning time", PredictorTests::overstay);
        run("Predictor: unknown occupant assumes an ESI-3 stay from now", PredictorTests::unknownOccupant);
        run("Predictor: ideal bed type wins ties, an earlier fallback wins otherwise", PredictorTests::idealOnTies);
        run("Predictor: custom stay lengths; missing entries fall back to defaults", PredictorTests::customLengthOfStay);
        run("Predictor: inputs are not mutated and the result is read-only", PredictorTests::inputsUntouched);
        run("Predictor: waits never decrease down a queue of identical patients", PredictorTests::monotonic);
        run("Predictor: zero-wait predictions match what autoAllocate really does", PredictorTests::matchesAutoAllocate);
    }

    // ------------------------------------------------------------------ tests

    private static void emptyQueue() {
        List<Bed> beds = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW));
        check(predictor().predict(List.of(), beds, id -> null, NOW).isEmpty(), "empty queue should give an empty map");
        check(predictor().predict(List.of(), List.of(), id -> null, NOW).isEmpty(), "no beds, no patients -> empty map");
    }

    private static void freeBed() {
        Patient p = waiting(1, 40, TriageLevel.URGENT);
        Prediction pr = predictor().predict(List.of(p),
                List.of(bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW.minusHours(3))), id -> null, NOW).get(1);
        check(pr != null, "no prediction for the waiting patient");
        check(pr.patientId() == 1, "wrong patient id " + pr.patientId());
        check(pr.minutesUntilBed() == 0, "free bed should mean 0 min, got " + pr.minutesUntilBed());
        check(pr.bedType() == BedType.GENERAL && !pr.noBedPossible(), "expected a general bed, got " + pr);
    }

    private static void allOccupied() {
        Map<Integer, Patient> occupants = new HashMap<>();
        occupants.put(100, admitted(100, TriageLevel.URGENT, NOW.minusMinutes(30), "GEN-1")); // 120 min stay
        List<Bed> beds = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, 100, NOW.minusMinutes(30)));
        Prediction pr = predictor().predict(List.of(waiting(1, 40, TriageLevel.URGENT)), beds, occupants::get, NOW).get(1);
        check(pr.minutesUntilBed() == 90 + CLEAN, "expected 90 left + 15 cleaning = 105, got " + pr.minutesUntilBed());
        check(pr.bedType() == BedType.GENERAL && !pr.noBedPossible(), "expected general bed, got " + pr);
    }

    private static void competing() {
        Patient first = waiting(1, 40, TriageLevel.URGENT);
        Patient second = waiting(2, 40, TriageLevel.URGENT);
        Map<Integer, Prediction> m = predictor().predict(List.of(first, second),
                List.of(bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW)), id -> null, NOW);
        check(m.get(1).minutesUntilBed() == 0, "first should get the free bed now");
        check(m.get(2).minutesUntilBed() == 120 + CLEAN, "second should wait 120 + 15 = 135, got " + m.get(2).minutesUntilBed());
        check(m.get(2).bedType() == BedType.GENERAL, "second should be predicted the same general bed");
    }

    private static void priorityOrder() {
        // ESI 2 (stay 180) and ESI 4 (stay 60) adults both accept a monitored bed; only one exists
        Patient emergent = waiting(1, 50, TriageLevel.EMERGENT);
        Patient minor = waiting(2, 30, TriageLevel.LESS_URGENT);
        List<Bed> beds = List.of(bed("MON-1", BedType.MONITORED, BedStatus.AVAILABLE, null, NOW));

        Map<Integer, Prediction> m = predictor().predict(List.of(emergent, minor), beds, id -> null, NOW);
        check(m.get(1).minutesUntilBed() == 0, "queue head should get the bed now");
        check(m.get(2).minutesUntilBed() == 180 + CLEAN, "ESI 4 should wait for the ESI 2 stay, got " + m.get(2).minutesUntilBed());

        m = predictor().predict(List.of(minor, emergent), beds, id -> null, NOW);
        check(m.get(2).minutesUntilBed() == 0, "reversed queue: ESI 4 is now first");
        check(m.get(1).minutesUntilBed() == 60 + CLEAN, "reversed queue: ESI 2 waits for the ESI 4 stay, got " + m.get(1).minutesUntilBed());
        check(new ArrayList<>(m.keySet()).equals(List.of(2, 1)), "result should keep queue order, got " + m.keySet());
    }

    private static void spillOver() {
        // 2 general beds, 5 ESI 4 adults (stay 60 + cleaning 15 = a 75-minute cycle)
        List<Patient> queue = new ArrayList<>();
        for (int i = 1; i <= 5; i++) queue.add(waiting(i, 30, TriageLevel.LESS_URGENT));
        List<Bed> beds = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW),
                bed("GEN-2", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW));
        Map<Integer, Prediction> m = predictor().predict(queue, beds, id -> null, NOW);
        long[] expected = {0, 0, 75, 75, 150};
        for (int i = 1; i <= 5; i++) {
            check(m.get(i).minutesUntilBed() == expected[i - 1],
                    "patient " + i + " expected " + expected[i - 1] + " min, got " + m.get(i).minutesUntilBed());
        }
    }

    private static void maintenanceOnly() {
        Patient p = waiting(1, 40, TriageLevel.URGENT);
        List<Bed> beds = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.MAINTENANCE, null, NOW),
                bed("MON-1", BedType.MONITORED, BedStatus.MAINTENANCE, null, NOW));
        Prediction pr = predictor().predict(List.of(p), beds, id -> null, NOW).get(1);
        check(pr.noBedPossible(), "maintenance beds never free up: " + pr);
        check(pr.minutesUntilBed() == -1 && pr.bedType() == null, "noBedPossible should carry -1 and no type: " + pr);

        Prediction none = predictor().predict(List.of(p), List.of(), id -> null, NOW).get(1);
        check(none.noBedPossible(), "no beds at all -> no bed possible");
    }

    private static void lowAcuityProtected() {
        Map<Integer, Patient> occupants = new HashMap<>();
        occupants.put(100, admitted(100, TriageLevel.URGENT, NOW, "GEN-1"));
        List<Bed> beds = List.of(
                bed("ICU-1", BedType.ICU, BedStatus.AVAILABLE, null, NOW),
                bed("TRM-1", BedType.TRAUMA, BedStatus.AVAILABLE, null, NOW),
                bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, 100, NOW));
        List<Patient> queue = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            queue.add(waiting(i, 20 + i * 10, i % 2 == 0 ? TriageLevel.LESS_URGENT : TriageLevel.NON_URGENT));
        }
        Map<Integer, Prediction> m = predictor().predict(queue, beds, occupants::get, NOW);
        for (Prediction pr : m.values()) {
            check(pr.bedType() == BedType.GENERAL, "ESI 4-5 patient " + pr.patientId() + " predicted into " + pr.bedType());
        }
        check(m.get(1).minutesUntilBed() == 120 + CLEAN, "first ESI 5 should wait for the general bed, got " + m.get(1));

        List<Bed> criticalOnly = List.of(bed("ICU-1", BedType.ICU, BedStatus.AVAILABLE, null, NOW),
                bed("TRM-1", BedType.TRAUMA, BedStatus.AVAILABLE, null, NOW));
        check(predictor().predict(List.of(waiting(9, 30, TriageLevel.NON_URGENT)), criticalOnly, id -> null, NOW)
                .get(9).noBedPossible(), "ESI 5 with only ICU/Trauma beds must get no bed");

        Patient critical = waiting(10, 60, TriageLevel.RESUSCITATION);
        check(predictor().predict(List.of(critical), criticalOnly, id -> null, NOW).get(10).bedType() == BedType.ICU,
                "ESI 1 should still be predicted into the ICU");
    }

    private static void adultsNotPediatric() {
        Map<Integer, Patient> occupants = new HashMap<>();
        occupants.put(100, admitted(100, TriageLevel.LESS_URGENT, NOW, "GEN-1"));
        List<Bed> beds = List.of(bed("PED-1", BedType.PEDIATRIC, BedStatus.AVAILABLE, null, NOW),
                bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, 100, NOW));
        Patient adult = waiting(1, 40, TriageLevel.LESS_URGENT);
        Patient elder = waiting(2, 80, TriageLevel.LESS_URGENT);
        Patient child = waiting(3, 6, TriageLevel.LESS_URGENT);
        Map<Integer, Prediction> m = predictor().predict(List.of(adult, elder, child), beds, occupants::get, NOW);
        check(m.get(1).bedType() == BedType.GENERAL && m.get(1).minutesUntilBed() == 60 + CLEAN,
                "adult should wait for the general bed, got " + m.get(1));
        check(m.get(2).bedType() == BedType.GENERAL, "geriatric patient predicted into " + m.get(2).bedType());
        check(m.get(3).bedType() == BedType.PEDIATRIC && m.get(3).minutesUntilBed() == 0,
                "child should take the free pediatric bed, got " + m.get(3));

        List<Bed> pediatricOnly = List.of(bed("PED-1", BedType.PEDIATRIC, BedStatus.AVAILABLE, null, NOW));
        check(predictor().predict(List.of(adult), pediatricOnly, id -> null, NOW).get(1).noBedPossible(),
                "adult with only pediatric beds must get no bed");
    }

    private static void cleaningBed() {
        Patient p = waiting(1, 40, TriageLevel.URGENT);
        Prediction recent = predictor().predict(List.of(p),
                List.of(bed("GEN-1", BedType.GENERAL, BedStatus.CLEANING, null, NOW.minusMinutes(5))), id -> null, NOW).get(1);
        check(recent.minutesUntilBed() == CLEAN - 5, "cleaning started 5 min ago -> 10 min, got " + recent.minutesUntilBed());

        Prediction overdue = predictor().predict(List.of(p),
                List.of(bed("GEN-1", BedType.GENERAL, BedStatus.CLEANING, null, NOW.minusMinutes(40))), id -> null, NOW).get(1);
        check(overdue.minutesUntilBed() == 0, "cleaning should be done by now, got " + overdue.minutesUntilBed());

        Prediction partial = predictor().predict(List.of(p),
                List.of(bed("GEN-1", BedType.GENERAL, BedStatus.CLEANING, null, NOW.minusSeconds(30))), id -> null, NOW).get(1);
        check(partial.minutesUntilBed() == CLEAN, "14.5 min should round up to 15, got " + partial.minutesUntilBed());
    }

    private static void overstay() {
        Map<Integer, Patient> occupants = new HashMap<>();
        occupants.put(100, admitted(100, TriageLevel.URGENT, NOW.minusMinutes(300), "GEN-1")); // 180 min over
        List<Bed> beds = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, 100, NOW.minusMinutes(300)));
        Prediction pr = predictor().predict(List.of(waiting(1, 40, TriageLevel.URGENT)), beds, occupants::get, NOW).get(1);
        check(pr.minutesUntilBed() == CLEAN, "overstaying occupant should leave now -> cleaning only, got " + pr.minutesUntilBed());
    }

    private static void unknownOccupant() {
        Patient p = waiting(1, 40, TriageLevel.URGENT);
        List<Bed> unknownId = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, 999, NOW));
        check(predictor().predict(List.of(p), unknownId, id -> null, NOW).get(1).minutesUntilBed() == 120 + CLEAN,
                "unknown occupant should be treated as a fresh ESI-3 stay");
        List<Bed> noId = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, null, NOW));
        check(predictor().predict(List.of(p), noId, id -> null, NOW).get(1).minutesUntilBed() == 120 + CLEAN,
                "occupied bed without a patient id should be treated as a fresh ESI-3 stay");
        check(predictor().predict(List.of(p), noId, null, NOW).get(1).minutesUntilBed() == 120 + CLEAN,
                "null occupant lookup should be tolerated");
    }

    private static void idealOnTies() {
        Patient p = waiting(1, 40, TriageLevel.URGENT); // ideal GENERAL, fallback MONITORED
        List<Bed> bothFree = List.of(bed("MON-1", BedType.MONITORED, BedStatus.AVAILABLE, null, NOW.minusHours(5)),
                bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW));
        check(predictor().predict(List.of(p), bothFree, id -> null, NOW).get(1).bedType() == BedType.GENERAL,
                "both free now -> ideal general bed");

        Map<Integer, Patient> occupants = new HashMap<>();
        occupants.put(100, admitted(100, TriageLevel.LESS_URGENT, NOW.minusMinutes(20), "MON-1"));
        occupants.put(101, admitted(101, TriageLevel.LESS_URGENT, NOW.minusMinutes(20), "GEN-1"));
        List<Bed> sameFreeTime = List.of(bed("MON-1", BedType.MONITORED, BedStatus.OCCUPIED, 100, NOW),
                bed("GEN-1", BedType.GENERAL, BedStatus.OCCUPIED, 101, NOW));
        Prediction tie = predictor().predict(List.of(p), sameFreeTime, occupants::get, NOW).get(1);
        check(tie.bedType() == BedType.GENERAL && tie.minutesUntilBed() == 40 + CLEAN,
                "equal free times -> ideal general bed after 55 min, got " + tie);

        occupants.put(100, admitted(100, TriageLevel.LESS_URGENT, NOW.minusMinutes(50), "MON-1"));
        Prediction earlier = predictor().predict(List.of(p), sameFreeTime, occupants::get, NOW).get(1);
        check(earlier.bedType() == BedType.MONITORED && earlier.minutesUntilBed() == 10 + CLEAN,
                "monitored bed frees first -> fallback after 25 min, got " + earlier);

        Patient critical = waiting(2, 60, TriageLevel.RESUSCITATION); // ideal ICU, fallbacks TRAUMA, MONITORED
        List<Bed> icuBusy = List.of(bed("ICU-1", BedType.ICU, BedStatus.CLEANING, null, NOW),
                bed("MON-1", BedType.MONITORED, BedStatus.AVAILABLE, null, NOW));
        Prediction fallback = predictor().predict(List.of(critical), icuBusy, id -> null, NOW).get(2);
        check(fallback.bedType() == BedType.MONITORED && fallback.minutesUntilBed() == 0,
                "ESI 1 should take a free monitored bed rather than wait for ICU cleaning, got " + fallback);
    }

    private static void customLengthOfStay() {
        Map<TriageLevel, Integer> los = new EnumMap<>(TriageLevel.class);
        los.put(TriageLevel.URGENT, 30);
        WaitTimePredictor custom = new WaitTimePredictor(new BedMatchingAlgorithm(), los, 10);
        List<Bed> beds = List.of(bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW));

        Map<Integer, Prediction> m = custom.predict(List.of(waiting(1, 40, TriageLevel.URGENT),
                waiting(2, 40, TriageLevel.LESS_URGENT), waiting(3, 40, TriageLevel.URGENT)), beds, id -> null, NOW);
        check(m.get(2).minutesUntilBed() == 30 + 10, "custom ESI 3 stay 30 + clean 10 = 40, got " + m.get(2).minutesUntilBed());
        check(m.get(3).minutesUntilBed() == 40 + 60 + 10, "missing ESI 4 entry should use default 60, got " + m.get(3).minutesUntilBed());

        los.put(TriageLevel.URGENT, 500); // the predictor keeps its own copy
        check(custom.predict(List.of(waiting(1, 40, TriageLevel.URGENT), waiting(2, 40, TriageLevel.URGENT)), beds, id -> null, NOW)
                .get(2).minutesUntilBed() == 40, "later changes to the caller's map must not leak in");

        WaitTimePredictor defaults = new WaitTimePredictor(new BedMatchingAlgorithm(), null, CLEAN);
        check(defaults.predict(List.of(waiting(1, 40, TriageLevel.URGENT), waiting(2, 40, TriageLevel.URGENT)), beds, id -> null, NOW)
                .get(2).minutesUntilBed() == 120 + CLEAN, "null stay map should use the defaults");
    }

    private static void inputsUntouched() {
        Map<Integer, Patient> occupants = new HashMap<>();
        occupants.put(100, admitted(100, TriageLevel.EMERGENT, NOW.minusMinutes(70), "MON-1"));
        List<Bed> beds = new ArrayList<>(List.of(
                bed("GEN-1", BedType.GENERAL, BedStatus.AVAILABLE, null, NOW.minusMinutes(90)),
                bed("MON-1", BedType.MONITORED, BedStatus.OCCUPIED, 100, NOW.minusMinutes(70)),
                bed("ICU-1", BedType.ICU, BedStatus.CLEANING, null, NOW.minusMinutes(3)),
                bed("PED-1", BedType.PEDIATRIC, BedStatus.MAINTENANCE, null, NOW.minusDays(1))));
        List<Patient> queue = new ArrayList<>(List.of(waiting(1, 50, TriageLevel.EMERGENT),
                waiting(2, 40, TriageLevel.URGENT), waiting(3, 7, TriageLevel.LESS_URGENT)));
        List<String> bedsBefore = beds.stream().map(PredictorTests::describe).toList();
        List<String> queueBefore = queue.stream().map(PredictorTests::describe).toList();
        String occupantBefore = describe(occupants.get(100));

        Map<Integer, Prediction> m = predictor().predict(queue, beds, occupants::get, NOW);

        check(m.size() == 3, "expected 3 predictions, got " + m.size());
        check(beds.stream().map(PredictorTests::describe).toList().equals(bedsBefore), "beds were mutated");
        check(queue.stream().map(PredictorTests::describe).toList().equals(queueBefore), "waiting patients were mutated");
        check(describe(occupants.get(100)).equals(occupantBefore), "occupant was mutated");
        boolean readOnly;
        try {
            m.put(99, new Prediction(99, 0, BedType.GENERAL, false));
            readOnly = false;
        } catch (UnsupportedOperationException expected) {
            readOnly = true;
        }
        check(readOnly, "result map should be unmodifiable");
    }

    private static void monotonic() {
        List<Patient> queue = new ArrayList<>();
        for (int i = 1; i <= 500; i++) queue.add(waiting(i, 40, TriageLevel.URGENT));
        List<Bed> beds = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            beds.add(bed("GEN-" + i, BedType.GENERAL, i % 3 == 0 ? BedStatus.CLEANING : BedStatus.AVAILABLE, null,
                    NOW.minusMinutes(i)));
            beds.add(bed("MON-" + i, BedType.MONITORED, i % 4 == 0 ? BedStatus.MAINTENANCE : BedStatus.AVAILABLE, null, NOW));
        }
        Map<Integer, Prediction> m = predictor().predict(queue, beds, id -> null, NOW);
        check(m.size() == 500, "expected 500 predictions, got " + m.size());
        long previous = 0;
        for (Patient p : queue) {
            Prediction pr = m.get(p.getId());
            check(!pr.noBedPossible() && pr.minutesUntilBed() >= previous,
                    "wait went down at patient " + p.getId() + ": " + previous + " -> " + pr.minutesUntilBed());
            previous = pr.minutesUntilBed();
        }
        // 35 usable beds, a 135-minute cycle: the last patient is in round 500/35 -> about 14 cycles
        check(previous >= 13 * 135 && previous <= 15 * 135, "last wait out of range: " + previous);
    }

    private static void matchesAutoAllocate() {
        File f = tempDbFile("predict");
        try (HospitalManager m = freshManager(f, "admin", "admin123")) {
            int bedCount = m.allBeds().size();
            m.simulateArrivals(bedCount + 15);
            List<Patient> queue = List.copyOf(m.waitingQueue());
            Map<Integer, Prediction> before = m.predictWaits();
            check(before.size() == queue.size(), "every waiting patient needs a prediction");

            m.autoAllocate();
            int predictedNow = 0;
            for (Patient p : queue) {
                Prediction pr = before.get(p.getId());
                boolean admittedNow = p.getStatus() == PatientStatus.ADMITTED;
                check(admittedNow == (pr.minutesUntilBed() == 0),
                        p + " predicted " + pr.minutesUntilBed() + " min but admitted=" + admittedNow);
                if (admittedNow) {
                    predictedNow++;
                    BedType actual = m.findBed(p.getAssignedBedId()).getType();
                    check(actual == pr.bedType(), p + " predicted " + pr.bedType() + " but got " + actual);
                }
            }
            check(predictedNow > 0, "some patients should have been placed");

            Map<Integer, Prediction> after = m.predictWaits();
            check(after.size() == m.waitingQueue().size(), "every remaining patient needs a prediction");
            for (Prediction pr : after.values()) {
                check(pr.noBedPossible() || pr.minutesUntilBed() > 0,
                        "nothing usable is free after autoAllocate, yet patient " + pr.patientId() + " predicted 0 min");
            }
        } finally {
            deleteQuietly(f);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static WaitTimePredictor predictor() {
        return new WaitTimePredictor(new BedMatchingAlgorithm(), WaitTimePredictor.defaultLengthOfStay(), CLEAN);
    }

    private static Patient waiting(int id, int age, TriageLevel level) {
        Patient p = PatientFactory.create("Patient " + id, age, "Female", "Abdominal pain", false, VITALS, 2,
                NOW.minusMinutes(20));
        p.setId(id);
        p.applyTriage(level, 50, 0, 100 - id); // priority only matters for the caller's ordering
        return p;
    }

    private static Patient admitted(int id, TriageLevel level, LocalDateTime when, String bedId) {
        Patient p = waiting(id, 45, level);
        p.markAdmitted(bedId, null, when);
        return p;
    }

    private static Bed bed(String id, BedType type, BedStatus status, Integer patientId, LocalDateTime updatedAt) {
        return new Bed(id, type.getDisplayName(), type, status, patientId, updatedAt);
    }

    private static String describe(Bed b) {
        return b.getBedId() + "|" + b.getType() + "|" + b.getStatus() + "|" + b.getPatientId() + "|" + b.getUpdatedAt();
    }

    private static String describe(Patient p) {
        return p.getId() + "|" + p.getStatus() + "|" + p.getTriageLevel() + "|" + p.getPriorityScore() + "|"
                + p.getAssignedBedId() + "|" + p.getAdmittedTime();
    }
}
