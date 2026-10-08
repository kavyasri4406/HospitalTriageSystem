package com.hospital.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Source of "now" for the whole backend. Supports fast-forwarding so wait-time
 * aging and overdue alerts can be demonstrated without waiting real minutes.
 */
public class HospitalClock {

    private Duration offset = Duration.ZERO;

    public LocalDateTime now() {
        return LocalDateTime.now().plus(offset).withNano(0);
    }

    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    public Duration getOffset() {
        return offset;
    }

    public void reset() {
        offset = Duration.ZERO;
    }
}
