package com.hospital.algorithms;

import com.hospital.model.Patient;
import com.hospital.model.TriageLevel;
import com.hospital.model.Vitals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emergency Severity Index (ESI v4) decision tree, followed by a numeric
 * severity score used to order patients that share the same ESI level.
 *
 * <pre>
 *  A. Requires immediate life-saving intervention?            yes -> ESI 1
 *  B. High-risk situation / confused / severe pain or distress? yes -> ESI 2
 *  C. How many resources will be needed?   0 -> ESI 5,  1 -> ESI 4,  2+ -> D
 *  D. Danger-zone vitals (age adjusted)?                       yes -> ESI 2, else ESI 3
 * </pre>
 */
public class EsiScoringAlgorithm {

    /** Complaints that are high-risk even when the patient looks stable. */
    private static final String[] HIGH_RISK_KEYWORDS = {
            "chest pain", "stroke", "seizure", "overdose", "suicid", "anaphyla", "sepsis",
            "unconscious", "head injury", "gunshot", "stab", "shortness of breath", "difficulty breathing",
            "severe bleeding", "heart attack", "poison", "burn", "pregnan", "slurred speech"
    };

    private static final double[] LEVEL_BASE_SCORE = {0, 85, 65, 45, 25, 10};
    private static final double MAX_VITALS_POINTS = 15;

    public TriageAssessment assess(Patient patient) {
        Vitals v = patient.getVitals();
        List<String> reasons = new ArrayList<>();
        TriageLevel level = decideLevel(patient, v, reasons);

        double vitalsPoints = Math.min(MAX_VITALS_POINTS, vitalsDeviationPoints(v));
        double raw = (LEVEL_BASE_SCORE[level.getEsi()] + vitalsPoints) * patient.ageRiskModifier();
        double severity = round1(Math.min(100, raw));
        if (patient.ageRiskModifier() != 1.0) {
            reasons.add(String.format(Locale.ROOT, "%s age-risk modifier x%.2f",
                    patient.getCategory().toLowerCase(Locale.ROOT), patient.ageRiskModifier()));
        }
        return new TriageAssessment(level, severity, List.copyOf(reasons));
    }

    private TriageLevel decideLevel(Patient p, Vitals v, List<String> reasons) {
        // ---- A: immediate life-saving intervention -------------------------------
        boolean lifeThreat = false;
        if (v.gcs() <= 8) { reasons.add("GCS " + v.gcs() + " (unresponsive)"); lifeThreat = true; }
        if (v.spo2() < 85) { reasons.add("SpO2 " + v.spo2() + "% (severe hypoxia)"); lifeThreat = true; }
        if (v.systolicBp() < 80) { reasons.add("SBP " + v.systolicBp() + " (shock)"); lifeThreat = true; }
        if (v.heartRate() < 40 || v.heartRate() > 150) { reasons.add("HR " + v.heartRate() + " (unstable)"); lifeThreat = true; }
        if (v.respiratoryRate() < 8 || v.respiratoryRate() > 35) { reasons.add("RR " + v.respiratoryRate() + " (respiratory failure risk)"); lifeThreat = true; }
        if (lifeThreat) {
            reasons.add(0, "Decision A: needs immediate life-saving intervention");
            return TriageLevel.RESUSCITATION;
        }

        // ---- B: high risk, confusion, severe pain ---------------------------------
        boolean highRisk = false;
        String complaintRisk = highRiskKeyword(p.getChiefComplaint());
        if (complaintRisk != null) { reasons.add("high-risk complaint: \"" + complaintRisk + "\""); highRisk = true; }
        if (v.gcs() < 14) { reasons.add("altered mental status (GCS " + v.gcs() + ")"); highRisk = true; }
        if (v.painScore() >= 8) { reasons.add("severe pain " + v.painScore() + "/10"); highRisk = true; }
        if (p.isTrauma() && (v.systolicBp() < 90 || v.heartRate() > 120)) {
            reasons.add("trauma with unstable haemodynamics"); highRisk = true;
        }
        if (highRisk) {
            reasons.add(0, "Decision B: high-risk situation");
            return TriageLevel.EMERGENT;
        }

        // ---- C: expected resources ------------------------------------------------
        int resources = p.getExpectedResources();
        if (resources == 0) {
            reasons.add("Decision C: no resources expected");
            return TriageLevel.NON_URGENT;
        }
        if (resources == 1) {
            reasons.add("Decision C: one resource expected");
            return TriageLevel.LESS_URGENT;
        }

        // ---- D: danger-zone vitals --------------------------------------------------
        List<String> danger = new ArrayList<>();
        if (v.heartRate() > p.dangerZoneHeartRate()) danger.add("HR " + v.heartRate() + " > " + p.dangerZoneHeartRate());
        if (v.respiratoryRate() > p.dangerZoneRespiratoryRate()) danger.add("RR " + v.respiratoryRate() + " > " + p.dangerZoneRespiratoryRate());
        if (v.spo2() < 92) danger.add("SpO2 " + v.spo2() + "% < 92%");
        if (v.temperature() >= 39.5 || v.temperature() < 35.0) danger.add(String.format(Locale.ROOT, "temperature %.1f°C", v.temperature()));
        if (!danger.isEmpty()) {
            reasons.add("Decision D: danger-zone vitals - " + String.join(", ", danger));
            return TriageLevel.EMERGENT;
        }
        reasons.add("Decision C: " + resources + " resources expected, vitals stable");
        return TriageLevel.URGENT;
    }

    /** Extra points (0-15) for how far vitals deviate from normal; separates patients inside one ESI level. */
    double vitalsDeviationPoints(Vitals v) {
        double points = 0;
        if (v.spo2() < 95) points += (95 - v.spo2()) * 0.8;
        if (v.heartRate() > 100) points += (v.heartRate() - 100) * 0.08;
        if (v.heartRate() < 50) points += (50 - v.heartRate()) * 0.15;
        if (v.systolicBp() < 90) points += (90 - v.systolicBp()) * 0.15;
        if (v.systolicBp() > 180) points += (v.systolicBp() - 180) * 0.05;
        if (v.respiratoryRate() > 20) points += (v.respiratoryRate() - 20) * 0.3;
        if (v.respiratoryRate() < 10) points += (10 - v.respiratoryRate()) * 0.5;
        if (v.temperature() > 38.0) points += (v.temperature() - 38.0) * 1.5;
        if (v.temperature() < 36.0) points += (36.0 - v.temperature()) * 1.5;
        points += v.painScore() * 0.4;
        points += (15 - v.gcs()) * 0.8;
        return points;
    }

    static String highRiskKeyword(String complaint) {
        if (complaint == null) return null;
        String text = complaint.toLowerCase(Locale.ROOT);
        for (String keyword : HIGH_RISK_KEYWORDS) {
            if (text.contains(keyword)) return keyword;
        }
        return null;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
