package com.hospital;

import com.hospital.algorithms.BedMatchingAlgorithm;
import com.hospital.algorithms.BedMatchingAlgorithm.BedMatch;
import com.hospital.algorithms.DoctorAllocationAlgorithm;
import com.hospital.algorithms.EsiScoringAlgorithm;
import com.hospital.algorithms.PatientPriorityComparator;
import com.hospital.algorithms.TriageAssessment;
import com.hospital.algorithms.WaitTimeAgingAlgorithm;
import com.hospital.datastructures.BedRegistry;
import com.hospital.datastructures.TriagePriorityQueue;
import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Doctor;
import com.hospital.model.GeriatricPatient;
import com.hospital.model.Patient;
import com.hospital.model.PatientFactory;
import com.hospital.model.PatientIntakeForm;
import com.hospital.model.PatientStatus;
import com.hospital.model.PediatricPatient;
import com.hospital.model.Specialty;
import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;
import com.hospital.persistence.DatabaseConfig;
import com.hospital.persistence.DatabaseManager;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.service.ValidationService;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Dependency-free test suite (no JUnit needed). Run with test.bat.
 * Exits with status 1 if any check fails.
 */
public class SelfTestRunner {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println("  HOSPITAL TRIAGE SYSTEM - SELF TESTS");
        System.out.println("=================================================================");

        run("Max-heap returns elements in priority order (5,000 random ops)", SelfTestRunner::heapRandomized);
        run("Max-heap remove(key) / update(key) keep the invariant", SelfTestRunner::heapRemoveAndUpdate);
        run("ESI-1 for life-threatening vitals", SelfTestRunner::esiLevelOne);
        run("ESI-2 for high-risk complaint / severe pain", SelfTestRunner::esiLevelTwo);
        run("ESI-3/4/5 by expected resources", SelfTestRunner::esiResources);
        run("ESI-2 upgrade on danger-zone vitals (age adjusted)", SelfTestRunner::esiDangerZone);
        run("Patient factory picks subclass by age (polymorphism)", SelfTestRunner::factory);
        run("Wait-time aging grows, caps, and cannot lift ESI-5 above ESI-2", SelfTestRunner::aging);
        run("Bed matcher: ideal type, fallback, ICU protected from ESI 4-5", SelfTestRunner::bedMatching);
        run("Doctor allocation prefers specialty, then lowest load", SelfTestRunner::doctorAllocation);
        run("Validation rejects implausible input", SelfTestRunner::validation);
        run("End-to-end with SQLite: register, admit, discharge, reload", SelfTestRunner::endToEnd);
        FeatureTests.runAll();
        PredictorTests.runAll();
        ReportTests.runAll();

        System.out.println("-----------------------------------------------------------------");
        System.out.printf("  %d passed, %d failed%n", passed, failed);
        System.out.println("=================================================================");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ tests

    private static void heapRandomized() {
        Random rnd = new Random(42);
        record Item(int id, int priority) {}
        TriagePriorityQueue<Integer, Item> heap =
                new TriagePriorityQueue<>(Comparator.comparingInt(Item::priority).thenComparing(Item::id), Item::id);
        List<Item> mirror = new ArrayList<>();
        int nextId = 0;
        for (int i = 0; i < 5000; i++) {
            if (mirror.isEmpty() || rnd.nextInt(3) > 0) {
                Item item = new Item(nextId++, rnd.nextInt(1000));
                heap.offer(item);
                mirror.add(item);
            } else {
                Item expected = mirror.stream().max(Comparator.comparingInt(Item::priority).thenComparing(Item::id)).orElseThrow();
                Item actual = heap.poll();
                check(expected.equals(actual), "poll returned " + actual + " expected " + expected);
                mirror.remove(expected);
            }
            check(heap.size() == mirror.size(), "size mismatch");
        }
        check(heap.isValidHeap(), "heap invariant broken");
    }

    private static void heapRemoveAndUpdate() {
        List<Patient> patients = new ArrayList<>();
        TriagePriorityQueue<Integer, Patient> heap =
                new TriagePriorityQueue<>(new PatientPriorityComparator(), Patient::getId);
        Random rnd = new Random(7);
        for (int i = 1; i <= 200; i++) {
            Patient p = adult("P" + i, 30, normalVitals(), 2, "Abdominal pain");
            p.setId(i);
            p.applyTriage(TriageLevel.URGENT, 45, 0, rnd.nextInt(100));
            heap.offer(p);
            patients.add(p);
        }
        for (int i = 0; i < 60; i++) {
            Patient victim = patients.remove(rnd.nextInt(patients.size()));
            check(heap.remove(victim.getId()) == victim, "remove returned wrong element");
            check(heap.isValidHeap(), "invariant broken after remove");
        }
        for (Patient p : patients) {
            p.applyTriage(p.getTriageLevel(), p.getSeverityScore(), 0, rnd.nextInt(100));
            heap.update(p.getId());
            check(heap.isValidHeap(), "invariant broken after update");
        }
        double last = Double.MAX_VALUE;
        while (!heap.isEmpty()) {
            double priority = heap.poll().getPriorityScore();
            check(priority <= last, "out of order");
            last = priority;
        }
    }

    private static void esiLevelOne() {
        TriageAssessment a = new EsiScoringAlgorithm().assess(
                adult("Crit", 50, new Vitals(160, 75, 40, 30, 82, 36.0, 5, 7), 5, "Collapse"));
        check(a.level() == TriageLevel.RESUSCITATION, "expected ESI 1, got " + a.level());
        check(a.severityScore() >= 85, "ESI 1 severity should be >= 85, got " + a.severityScore());
    }

    private static void esiLevelTwo() {
        EsiScoringAlgorithm esi = new EsiScoringAlgorithm();
        check(esi.assess(adult("A", 55, normalVitals(), 2, "Crushing chest pain")).level() == TriageLevel.EMERGENT,
                "chest pain should be ESI 2");
        check(esi.assess(adult("B", 30, new Vitals(90, 125, 80, 18, 98, 37, 9, 15), 1, "Back pain")).level() == TriageLevel.EMERGENT,
                "pain 9/10 should be ESI 2");
    }

    private static void esiResources() {
        EsiScoringAlgorithm esi = new EsiScoringAlgorithm();
        check(esi.assess(adult("A", 30, normalVitals(), 3, "Abdominal pain")).level() == TriageLevel.URGENT, "3 resources -> ESI 3");
        check(esi.assess(adult("B", 30, normalVitals(), 1, "Laceration")).level() == TriageLevel.LESS_URGENT, "1 resource -> ESI 4");
        check(esi.assess(adult("C", 30, normalVitals(), 0, "Sore throat")).level() == TriageLevel.NON_URGENT, "0 resources -> ESI 5");
    }

    private static void esiDangerZone() {
        EsiScoringAlgorithm esi = new EsiScoringAlgorithm();
        Vitals hr130 = new Vitals(130, 120, 80, 18, 97, 37, 3, 15);
        check(esi.assess(adult("Adult", 30, hr130, 2, "Abdominal pain")).level() == TriageLevel.EMERGENT,
                "adult HR 130 with 2 resources -> ESI 2");
        Patient toddler = PatientFactory.create("Toddler", 2, "Male", "Abdominal pain", false, hr130, 2, LocalDateTime.now());
        check(esi.assess(toddler).level() == TriageLevel.URGENT, "toddler HR 130 is normal -> ESI 3");
    }

    private static void factory() {
        Vitals v = normalVitals();
        check(PatientFactory.create("Kid", 6, "Female", "x", false, v, 1, LocalDateTime.now()) instanceof PediatricPatient, "age 6 -> pediatric");
        check(PatientFactory.create("Elder", 80, "Male", "x", false, v, 1, LocalDateTime.now()) instanceof GeriatricPatient, "age 80 -> geriatric");
        Patient adult = PatientFactory.create("Adult", 40, "Male", "x", false, v, 1, LocalDateTime.now());
        check(adult.getCategory().equals("ADULT"), "age 40 -> adult");
        check(PatientFactory.create("Kid", 6, "Female", "x", false, v, 1, LocalDateTime.now()).standardBedType() == BedType.PEDIATRIC,
                "children go to the pediatric ward");
    }

    private static void aging() {
        WaitTimeAgingAlgorithm aging = new WaitTimeAgingAlgorithm();
        check(aging.agingBonus(TriageLevel.NON_URGENT, 0) == 0, "no wait, no bonus");
        check(aging.agingBonus(TriageLevel.NON_URGENT, 30) > aging.agingBonus(TriageLevel.NON_URGENT, 10), "bonus grows");
        check(aging.agingBonus(TriageLevel.RESUSCITATION, 100) == 0, "ESI 1 does not age");
        // worst-case ESI 5 severity: (base 10 + max vitals points 15) x max age modifier 1.2 = 30
        double maxEsi5 = aging.priority(30, TriageLevel.NON_URGENT, 10_000);
        check(maxEsi5 < 65, "ESI 5 priority must stay below fresh ESI 2 base (65), got " + maxEsi5);
        double agedEsi4 = aging.priority(25, TriageLevel.LESS_URGENT, 90);
        check(agedEsi4 > 45, "long-waiting ESI 4 should overtake a fresh ESI 3, got " + agedEsi4);
    }

    private static void bedMatching() {
        BedMatchingAlgorithm matcher = new BedMatchingAlgorithm();
        BedRegistry beds = new BedRegistry();
        beds.add(new Bed("ICU-1", "ICU", BedType.ICU));
        beds.add(new Bed("MON-1", "Mon", BedType.MONITORED));
        beds.add(new Bed("GEN-1", "Gen", BedType.GENERAL));

        Patient critical = triaged(adult("Crit", 50, normalVitals(), 3, "x"), TriageLevel.RESUSCITATION);
        BedMatch m = matcher.findBestBed(critical, beds);
        check(m.found() && m.bed().getType() == BedType.ICU && !m.fallback(), "ESI 1 -> ICU");

        beds.findById("ICU-1").occupy(99, LocalDateTime.now());
        m = matcher.findBestBed(critical, beds);
        check(m.found() && m.bed().getType() == BedType.MONITORED && m.fallback(), "ICU full -> monitored fallback");

        beds.findById("ICU-1").setStatus(BedStatus.AVAILABLE, LocalDateTime.now());
        beds.findById("GEN-1").occupy(98, LocalDateTime.now());
        beds.findById("MON-1").occupy(97, LocalDateTime.now());
        Patient minor = triaged(adult("Minor", 30, normalVitals(), 0, "x"), TriageLevel.NON_URGENT);
        check(!matcher.findBestBed(minor, beds).found(), "ESI 5 must never take the ICU bed");
    }

    private static void doctorAllocation() {
        DoctorAllocationAlgorithm alloc = new DoctorAllocationAlgorithm();
        Doctor er = new Doctor(1, "ER", Specialty.EMERGENCY_MEDICINE, 5, 0, true);
        Doctor trauma = new Doctor(2, "Trauma", Specialty.TRAUMA_SURGERY, 3, 2, true);
        Doctor cardio = new Doctor(3, "Cardio", Specialty.CARDIOLOGY, 4, 0, true);
        List<Doctor> roster = List.of(er, trauma, cardio);

        Patient injured = triaged(PatientFactory.create("Hurt", 30, "Male", "Fall", true, normalVitals(), 2, LocalDateTime.now()),
                TriageLevel.URGENT);
        check(alloc.selectDoctor(injured, roster) == trauma, "trauma patient -> trauma surgeon");

        trauma.assignPatient(); // now at capacity
        check(alloc.selectDoctor(injured, roster) == er, "trauma surgeon full -> ER generalist");

        Patient chest = triaged(adult("Chest", 60, normalVitals(), 2, "Chest pain"), TriageLevel.EMERGENT);
        check(alloc.selectDoctor(chest, roster) == cardio, "chest pain -> cardiology");
        check(alloc.selectDoctor(chest, List.of(new Doctor(9, "Off", Specialty.CARDIOLOGY, 4, 0, false))) == null,
                "off-duty doctors are never selected");
    }

    private static void validation() {
        ValidationService v = new ValidationService();
        check(v.validate(form("Jane Doe", 30, 80, 120, 80)).isValid(), "valid form rejected");
        check(!v.validate(form("", 30, 80, 120, 80)).isValid(), "blank name accepted");
        check(!v.validate(form("Jane", 150, 80, 120, 80)).isValid(), "age 150 accepted");
        check(!v.validate(form("Jane", 30, 400, 120, 80)).isValid(), "HR 400 accepted");
        check(!v.validate(form("Jane", 30, 80, 100, 110)).isValid(), "diastolic > systolic accepted");
        check(!v.validate(form("J4n3 <script>", 30, 80, 120, 80)).isValid(), "invalid characters accepted");
    }

    private static void endToEnd() {
        File dbFile = new File(System.getProperty("java.io.tmpdir"), "triage-selftest-" + System.nanoTime() + ".db");
        try {
            try (HospitalManager m = new HospitalManager(DatabaseManager.connect(DatabaseConfig.sqlite(dbFile.getPath())))) {
                m.start();
                check(m.login("admin", "admin123").success(), "admin login failed");
                int beds = m.allBeds().size();
                check(beds > 0 && !m.allDoctors().isEmpty(), "seed data missing");

                OperationResult minor = m.registerPatient(form("Minor Case", 30, 80, 120, 80));
                OperationResult critical = m.registerPatient(new PatientIntakeForm("Critical Case", 60, "Male",
                        "Unresponsive after collapse", false, 35, 70, 40, 6, 80, 35.5, 0, 6, 5));
                check(minor.success() && critical.success(), "registration failed: " + minor.message() + " / " + critical.message());
                check(critical.patient().getTriageLevel() == TriageLevel.RESUSCITATION, "critical not ESI 1");
                check(m.waitingQueue().get(0) == critical.patient(), "ESI 1 should be first in queue");

                OperationResult admit = m.admitNext();
                check(admit.success(), "admit failed: " + admit.message());
                Patient admitted = critical.patient();
                check(admitted.getStatus() == PatientStatus.ADMITTED, "status not ADMITTED");
                check(m.findBed(admitted.getAssignedBedId()).getType() == BedType.ICU, "critical not placed in ICU");
                check(admitted.getAssignedDoctorId() != null, "no doctor dispatched");
                check(m.waitingQueue().size() == 1, "queue should have 1 left");

                OperationResult discharge = m.dischargePatient(admitted.getId());
                check(discharge.success(), "discharge failed");
                check(m.findBed(admitted.getAssignedBedId()).getStatus() == BedStatus.CLEANING, "bed should be CLEANING");
                check(m.markBedClean(admitted.getAssignedBedId()).success(), "mark clean failed");
            }
            // reopen: in-memory state must be rebuilt from the database
            try (HospitalManager m = new HospitalManager(DatabaseManager.connect(DatabaseConfig.sqlite(dbFile.getPath())))) {
                m.start();
                check(m.login("admin", "admin123").success(), "admin login failed");
                check(m.waitingQueue().size() == 1, "waiting patient not reloaded from DB");
                check(m.waitingQueue().get(0).getFullName().equals("Minor Case"), "wrong patient reloaded");
                check(m.analytics().patientsByStatus().get(PatientStatus.DISCHARGED) == 1, "discharge not persisted");
                check(m.analytics().bedsAvailable() == m.allBeds().size(), "all beds should be available after reload");
            }
        } finally {
            if (dbFile.exists() && !dbFile.delete()) dbFile.deleteOnExit();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Patient adult(String name, int age, Vitals v, int resources, String complaint) {
        return PatientFactory.create(name, age, "Female", complaint, false, v, resources, LocalDateTime.now());
    }

    private static Patient triaged(Patient p, TriageLevel level) {
        p.applyTriage(level, 50, 0, 50);
        return p;
    }

    private static Vitals normalVitals() {
        return new Vitals(80, 120, 80, 16, 98, 37.0, 2, 15);
    }

    private static PatientIntakeForm form(String name, int age, int hr, int sbp, int dbp) {
        return new PatientIntakeForm(name, age, "Female", "Sore throat", false, hr, sbp, dbp, 16, 98, 37.0, 2, 15, 0);
    }

    /**
     * Opens a brand-new SQLite database in the temp folder, starts a manager on it and logs in.
     * The caller must close the manager and delete the file (see {@link #deleteQuietly}).
     */
    static HospitalManager freshManager(File dbFile, String username, String password) {
        HospitalManager m = new HospitalManager(DatabaseManager.connect(DatabaseConfig.sqlite(dbFile.getPath())));
        m.start();
        if (username != null) check(m.login(username, password).success(), "login failed for " + username);
        return m;
    }

    static File tempDbFile(String prefix) {
        return new File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime() + ".db");
    }

    static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    @FunctionalInterface
    interface Test { void run() throws Exception; }

    static void run(String name, Test test) {
        try {
            test.run();
            passed++;
            System.out.println("  [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + "\n         -> " + t);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
