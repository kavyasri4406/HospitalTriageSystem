package com.hospital.service;

import com.hospital.algorithms.BedMatchingAlgorithm;
import com.hospital.algorithms.BedMatchingAlgorithm.BedMatch;
import com.hospital.datastructures.BedRegistry;
import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Patient;
import com.hospital.persistence.dao.BedDAO;

import java.util.List;

/** Bed inventory management and bed-to-patient matching. */
public class BedAllocationService {

    private final BedRegistry registry = new BedRegistry();
    private final BedDAO bedDAO;
    private final BedMatchingAlgorithm matcher;
    private final HospitalClock clock;

    public BedAllocationService(BedDAO bedDAO, BedMatchingAlgorithm matcher, HospitalClock clock) {
        this.bedDAO = bedDAO;
        this.matcher = matcher;
        this.clock = clock;
    }

    public void load() {
        registry.clear();
        bedDAO.findAll().forEach(registry::add);
    }

    public BedMatch findBed(Patient patient) {
        return matcher.findBestBed(patient, registry);
    }

    public BedType idealBedType(Patient patient) {
        return matcher.idealBedType(patient);
    }

    /** Marks the bed occupied in memory and in the database (caller supplies the transaction). */
    public void occupy(Bed bed, Patient patient) {
        bed.occupy(patient.getId(), clock.now());
        bedDAO.update(bed);
    }

    /** Moves an admitted patient: old bed goes to CLEANING, new bed becomes OCCUPIED. */
    public void transfer(Patient patient, Bed from, Bed to) {
        if (!to.isAvailable()) {
            throw new IllegalStateException(to.getBedId() + " is not available");
        }
        to.occupy(patient.getId(), clock.now());
        bedDAO.update(to);
        from.release(clock.now());
        bedDAO.update(from);
    }

    /** Bed types the patient may use, best first (see {@link BedMatchingAlgorithm#candidateBedTypes}). */
    public List<BedType> candidateBedTypes(Patient patient) {
        return matcher.candidateBedTypes(patient);
    }

    public BedMatchingAlgorithm matcher() {
        return matcher;
    }

    /** Patient leaves: bed goes to CLEANING. */
    public void release(String bedId) {
        Bed bed = require(bedId);
        bed.release(clock.now());
        bedDAO.update(bed);
    }

    public void markClean(String bedId) {
        Bed bed = require(bedId);
        if (bed.getStatus() != BedStatus.CLEANING) {
            throw new IllegalStateException(bedId + " is not awaiting cleaning");
        }
        bed.setStatus(BedStatus.AVAILABLE, clock.now());
        bedDAO.update(bed);
    }

    /** Toggles AVAILABLE <-> MAINTENANCE. Occupied beds cannot be taken out of service. */
    public BedStatus toggleMaintenance(String bedId) {
        Bed bed = require(bedId);
        BedStatus next = switch (bed.getStatus()) {
            case AVAILABLE, CLEANING -> BedStatus.MAINTENANCE;
            case MAINTENANCE -> BedStatus.AVAILABLE;
            case OCCUPIED -> throw new IllegalStateException(bedId + " is occupied");
        };
        bed.setStatus(next, clock.now());
        bedDAO.update(bed);
        return next;
    }

    public Bed findById(String bedId) { return registry.findById(bedId); }

    public List<Bed> allBeds() { return registry.all(); }

    public int availableCount() { return registry.count(BedStatus.AVAILABLE); }

    public int availableCount(BedType type) { return registry.count(type, BedStatus.AVAILABLE); }

    public int totalCount() { return registry.size(); }

    public int totalCount(BedType type) { return registry.ofType(type).size(); }

    public int count(BedStatus status) { return registry.count(status); }

    public int count(BedType type, BedStatus status) { return registry.count(type, status); }

    /** Occupied beds as a fraction of beds in service (excludes maintenance). */
    public double occupancyRate() {
        int inService = registry.size() - registry.count(BedStatus.MAINTENANCE);
        return inService == 0 ? 0 : (double) registry.count(BedStatus.OCCUPIED) / inService;
    }

    private Bed require(String bedId) {
        Bed bed = registry.findById(bedId);
        if (bed == null) throw new IllegalArgumentException("Unknown bed " + bedId);
        return bed;
    }
}
