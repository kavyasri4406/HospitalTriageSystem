package com.hospital.algorithms;

import com.hospital.model.TriageLevel;

import java.util.List;

/**
 * Output of the ESI algorithm.
 *
 * @param level         assigned ESI level
 * @param severityScore 0-100 clinical severity (higher = sicker), before wait aging
 * @param reasons       human-readable explanation of how the level was reached
 */
public record TriageAssessment(TriageLevel level, double severityScore, List<String> reasons) {
}
