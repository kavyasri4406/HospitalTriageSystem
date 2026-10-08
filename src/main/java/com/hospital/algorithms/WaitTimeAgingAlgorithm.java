package com.hospital.algorithms;

import com.hospital.model.TriageLevel;

/**
 * Anti-starvation "aging": the longer a patient waits, the more priority they gain,
 * so low-acuity patients are not pushed back forever by a stream of new arrivals.
 *
 * <pre>
 *   bonus = min(MAX_BONUS, minutesWaited * rate[level]) + (overdue ? OVERDUE_BOOST : 0)
 *   priority = severityScore + bonus
 * </pre>
 *
 * ESI 1 patients do not age (they are already seen immediately). The caps are chosen
 * so a patient can overtake the level directly above them, but an ESI 5 can never
 * outrank a fresh ESI 1 or 2.
 */
public class WaitTimeAgingAlgorithm {

    /** Points per minute waited, indexed by ESI level (index 0 unused). */
    private static final double[] RATE_PER_MINUTE = {0, 0.0, 0.10, 0.25, 0.35, 0.45};
    public static final double MAX_BONUS = 25.0;
    public static final double OVERDUE_BOOST = 5.0;

    public double agingBonus(TriageLevel level, long minutesWaited) {
        if (minutesWaited <= 0) return 0;
        double bonus = Math.min(MAX_BONUS, minutesWaited * RATE_PER_MINUTE[level.getEsi()]);
        if (level.getEsi() > 1 && minutesWaited > level.getTargetWaitMinutes()) {
            bonus += OVERDUE_BOOST;
        }
        return Math.round(bonus * 10.0) / 10.0;
    }

    public double priority(double severityScore, TriageLevel level, long minutesWaited) {
        return Math.round((severityScore + agingBonus(level, minutesWaited)) * 10.0) / 10.0;
    }
}
