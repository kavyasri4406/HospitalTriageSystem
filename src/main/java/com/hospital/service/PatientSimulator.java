package com.hospital.service;

import com.hospital.model.PatientIntakeForm;

import java.util.Random;

/** Generates realistic random arrivals for demonstrations and load testing. */
public class PatientSimulator {

    private static final String[] FIRST = {
            "Aarav", "Diya", "Rohan", "Meera", "Arjun", "Kavya", "Vikram", "Sneha", "Liam", "Olivia",
            "Noah", "Emma", "Ethan", "Ava", "Lucas", "Mia", "Ishaan", "Sara", "Kabir", "Nisha",
            "Omar", "Leila", "Mateo", "Sofia", "Hiro", "Yuki", "Kwame", "Amara", "Ravi", "Pooja"
    };
    private static final String[] LAST = {
            "Sharma", "Reddy", "Iyer", "Gupta", "Nair", "Khan", "Singh", "Das", "Smith", "Johnson",
            "Brown", "Garcia", "Martinez", "Lee", "Wilson", "Chen", "Kumar", "Varma", "Ali", "Okafor"
    };

    /** Clinical scenario templates: complaint, trauma flag, and vitals ranges. */
    private record Scenario(String complaint, boolean trauma, int resources,
                            int hrMin, int hrMax, int sbpMin, int sbpMax, int rrMin, int rrMax,
                            int spo2Min, int spo2Max, double tMin, double tMax,
                            int painMin, int painMax, int gcsMin, int gcsMax) {}

    private static final Scenario[] SCENARIOS = {
            new Scenario("Cardiac arrest, unresponsive", false, 5, 30, 45, 55, 75, 4, 8, 70, 82, 35.0, 36.5, 0, 0, 3, 6),
            new Scenario("Multiple trauma - road traffic accident", true, 5, 125, 155, 70, 88, 26, 36, 84, 92, 35.5, 36.8, 8, 10, 8, 13),
            new Scenario("Crushing chest pain radiating to left arm", false, 4, 95, 125, 140, 190, 18, 26, 90, 96, 36.5, 37.5, 7, 9, 15, 15),
            new Scenario("Sudden slurred speech and facial droop (stroke)", false, 4, 80, 105, 160, 210, 16, 22, 93, 98, 36.5, 37.4, 0, 3, 12, 14),
            new Scenario("Severe shortness of breath, asthma attack", false, 3, 110, 135, 110, 140, 28, 34, 86, 91, 36.8, 37.6, 3, 6, 15, 15),
            new Scenario("High fever with confusion, suspected sepsis", false, 4, 110, 135, 80, 98, 22, 28, 90, 94, 39.2, 40.3, 4, 6, 12, 14),
            new Scenario("Abdominal pain, vomiting", false, 3, 88, 108, 105, 135, 16, 22, 95, 99, 37.2, 38.6, 5, 7, 15, 15),
            new Scenario("Fall with suspected wrist fracture", true, 2, 78, 98, 115, 140, 14, 18, 97, 100, 36.5, 37.2, 4, 7, 15, 15),
            new Scenario("Deep laceration on forearm", true, 1, 75, 95, 115, 135, 14, 18, 97, 100, 36.5, 37.1, 3, 5, 15, 15),
            new Scenario("Persistent cough and mild fever", false, 1, 72, 92, 110, 130, 15, 19, 95, 99, 37.5, 38.4, 1, 3, 15, 15),
            new Scenario("Sore throat", false, 0, 70, 88, 110, 128, 13, 17, 97, 100, 36.8, 37.8, 2, 3, 15, 15),
            new Scenario("Prescription refill, minor rash", false, 0, 65, 85, 110, 128, 12, 16, 98, 100, 36.5, 37.0, 0, 1, 15, 15),
            new Scenario("Kidney stone - severe flank pain", false, 2, 95, 115, 130, 160, 18, 22, 96, 99, 36.8, 37.6, 8, 10, 15, 15),
            new Scenario("Palpitations and dizziness", false, 2, 105, 140, 100, 125, 16, 22, 95, 99, 36.6, 37.2, 1, 3, 15, 15),
    };

    private final Random random;

    public PatientSimulator() {
        this(new Random());
    }

    public PatientSimulator(Random random) {
        this.random = random;
    }

    public PatientIntakeForm randomArrival() {
        Scenario s = SCENARIOS[random.nextInt(SCENARIOS.length)];
        int roll = random.nextInt(100);
        int age = roll < 15 ? 1 + random.nextInt(15) : roll < 75 ? 16 + random.nextInt(49) : 65 + random.nextInt(28);
        int hr = between(s.hrMin, s.hrMax);
        if (age <= 8) hr += 20; // children have faster heart rates
        int sbp = between(s.sbpMin, s.sbpMax);
        int dbp = Math.max(30, Math.min(sbp - 20, (int) (sbp * (0.55 + random.nextDouble() * 0.12))));
        double temp = Math.round((s.tMin + random.nextDouble() * (s.tMax - s.tMin)) * 10) / 10.0;
        String gender = random.nextInt(100) < 48 ? "Male" : random.nextInt(100) < 96 ? "Female" : "Other";
        return new PatientIntakeForm(
                FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)],
                age, gender, s.complaint, s.trauma,
                hr, sbp, dbp, between(s.rrMin, s.rrMax), between(s.spo2Min, s.spo2Max), temp,
                between(s.painMin, s.painMax), between(s.gcsMin, s.gcsMax), s.resources);
    }

    /** Minutes the simulated patient has already been waiting when registered (to show aging). */
    public int randomMinutesAlreadyWaiting() {
        return random.nextInt(45);
    }

    private int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
