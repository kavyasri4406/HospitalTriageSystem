package com.hospital.datastructures;

import com.hospital.model.Doctor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Doctors on the roster: ordered {@link ArrayList} plus a {@link HashMap} index by id. */
public class DoctorRoster {

    private final List<Doctor> doctors = new ArrayList<>();
    private final Map<Integer, Doctor> byId = new HashMap<>();

    public void add(Doctor doctor) {
        doctors.add(doctor);
        byId.put(doctor.getId(), doctor);
    }

    public Doctor findById(int id) { return byId.get(id); }

    public List<Doctor> all() { return Collections.unmodifiableList(doctors); }

    public List<Doctor> available() {
        List<Doctor> result = new ArrayList<>();
        for (Doctor d : doctors) {
            if (d.canAcceptPatient()) result.add(d);
        }
        return result;
    }

    public int size() { return doctors.size(); }

    public void clear() {
        doctors.clear();
        byId.clear();
    }
}
