package com.hospital.service;

import com.hospital.algorithms.DoctorAllocationAlgorithm;
import com.hospital.datastructures.DoctorRoster;
import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.model.Specialty;
import com.hospital.persistence.dao.DoctorDAO;

import java.util.List;

/** Assigns doctors to admitted patients and tracks their workload. */
public class DoctorDispatchService {

    private final DoctorRoster roster = new DoctorRoster();
    private final DoctorDAO doctorDAO;
    private final DoctorAllocationAlgorithm allocator;

    public DoctorDispatchService(DoctorDAO doctorDAO, DoctorAllocationAlgorithm allocator) {
        this.doctorDAO = doctorDAO;
        this.allocator = allocator;
    }

    public void load() {
        roster.clear();
        doctorDAO.findAll().forEach(roster::add);
    }

    /** Best doctor for the patient, or null if every on-duty doctor is at capacity. */
    public Doctor selectDoctor(Patient patient) {
        return allocator.selectDoctor(patient, roster.all());
    }

    public Specialty requiredSpecialty(Patient patient) {
        return allocator.requiredSpecialty(patient);
    }

    public void assign(Doctor doctor) {
        doctor.assignPatient();
        doctorDAO.update(doctor);
    }

    public void release(Integer doctorId) {
        if (doctorId == null) return;
        Doctor doctor = roster.findById(doctorId);
        if (doctor == null) return;
        doctor.releasePatient();
        doctorDAO.update(doctor);
    }

    public boolean toggleDuty(int doctorId) {
        Doctor doctor = roster.findById(doctorId);
        if (doctor == null) throw new IllegalArgumentException("Unknown doctor " + doctorId);
        doctor.setOnDuty(!doctor.isOnDuty());
        doctorDAO.update(doctor);
        return doctor.isOnDuty();
    }

    public Doctor findById(Integer id) { return id == null ? null : roster.findById(id); }

    public List<Doctor> allDoctors() { return roster.all(); }

    public int availableCount() { return roster.available().size(); }

    public int onDutyCount() {
        int n = 0;
        for (Doctor d : roster.all()) if (d.isOnDuty()) n++;
        return n;
    }
}
