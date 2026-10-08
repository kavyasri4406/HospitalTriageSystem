package com.hospital.model;

import java.time.LocalDateTime;

/** A physical bed in a ward. Mutable state: status and current occupant. */
public class Bed {
    private final String bedId;
    private final String wardName;
    private final BedType type;
    private BedStatus status;
    private Integer patientId;
    private LocalDateTime updatedAt;

    public Bed(String bedId, String wardName, BedType type) {
        this(bedId, wardName, type, BedStatus.AVAILABLE, null, LocalDateTime.now());
    }

    public Bed(String bedId, String wardName, BedType type, BedStatus status,
               Integer patientId, LocalDateTime updatedAt) {
        this.bedId = bedId;
        this.wardName = wardName;
        this.type = type;
        this.status = status;
        this.patientId = patientId;
        this.updatedAt = updatedAt;
    }

    public boolean isAvailable() { return status == BedStatus.AVAILABLE; }

    public void occupy(int patientId, LocalDateTime when) {
        if (!isAvailable()) {
            throw new IllegalStateException("Bed " + bedId + " is not available (" + status + ")");
        }
        this.patientId = patientId;
        this.status = BedStatus.OCCUPIED;
        this.updatedAt = when;
    }

    /** Frees the bed; it must be cleaned before the next patient. */
    public void release(LocalDateTime when) {
        this.patientId = null;
        this.status = BedStatus.CLEANING;
        this.updatedAt = when;
    }

    public void setStatus(BedStatus status, LocalDateTime when) {
        if (status != BedStatus.OCCUPIED) {
            this.patientId = null;
        }
        this.status = status;
        this.updatedAt = when;
    }

    public String getBedId() { return bedId; }
    public String getWardName() { return wardName; }
    public BedType getType() { return type; }
    public BedStatus getStatus() { return status; }
    public Integer getPatientId() { return patientId; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return bedId + " (" + type.getDisplayName() + ", " + status + ")";
    }
}
