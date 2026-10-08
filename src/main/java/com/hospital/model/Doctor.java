package com.hospital.model;

/** An on-call physician who can be dispatched to admitted patients. */
public class Doctor {
    private int id;
    private final String name;
    private final Specialty specialty;
    private final int maxPatients;
    private int activePatients;
    private boolean onDuty;

    public Doctor(int id, String name, Specialty specialty, int maxPatients, int activePatients, boolean onDuty) {
        this.id = id;
        this.name = name;
        this.specialty = specialty;
        this.maxPatients = maxPatients;
        this.activePatients = activePatients;
        this.onDuty = onDuty;
    }

    public boolean canAcceptPatient() {
        return onDuty && activePatients < maxPatients;
    }

    /** Fraction of capacity in use, 0.0 to 1.0. */
    public double loadRatio() {
        return maxPatients == 0 ? 1.0 : (double) activePatients / maxPatients;
    }

    public void assignPatient() {
        if (!canAcceptPatient()) {
            throw new IllegalStateException(name + " cannot accept more patients");
        }
        activePatients++;
    }

    public void releasePatient() {
        if (activePatients > 0) activePatients--;
    }

    public String getStatusLabel() {
        if (!onDuty) return "OFF DUTY";
        if (activePatients >= maxPatients) return "AT CAPACITY";
        if (activePatients > 0) return "BUSY";
        return "AVAILABLE";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public Specialty getSpecialty() { return specialty; }
    public int getMaxPatients() { return maxPatients; }
    public int getActivePatients() { return activePatients; }
    public boolean isOnDuty() { return onDuty; }
    public void setOnDuty(boolean onDuty) { this.onDuty = onDuty; }

    @Override
    public String toString() {
        return name + " (" + specialty.getDisplayName() + ")";
    }
}
