package com.hospital.datastructures;

import com.hospital.model.Patient;
import com.hospital.model.PatientStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory index of every patient seen this session.
 * Two {@link HashMap}s give O(1) lookup by database id and by MRN.
 */
public class PatientRegistry {

    private final Map<Integer, Patient> byId = new HashMap<>();
    private final Map<String, Patient> byMrn = new HashMap<>();

    public void add(Patient patient) {
        byId.put(patient.getId(), patient);
        if (patient.getMrn() != null) {
            byMrn.put(patient.getMrn(), patient);
        }
    }

    public Patient findById(int id) { return byId.get(id); }

    public Patient findByMrn(String mrn) { return byMrn.get(mrn); }

    public boolean contains(int id) { return byId.containsKey(id); }

    public Collection<Patient> all() { return Collections.unmodifiableCollection(byId.values()); }

    public List<Patient> withStatus(PatientStatus status) {
        List<Patient> result = new ArrayList<>();
        for (Patient p : byId.values()) {
            if (p.getStatus() == status) result.add(p);
        }
        return result;
    }

    public int size() { return byId.size(); }

    public void clear() {
        byId.clear();
        byMrn.clear();
    }
}
