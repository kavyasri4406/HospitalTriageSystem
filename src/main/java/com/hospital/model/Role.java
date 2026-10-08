package com.hospital.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Staff roles and what each one may do (role-based access control). */
public enum Role {
    NURSE("Nurse", EnumSet.of(
            Permission.REGISTER_PATIENT, Permission.REASSESS_PATIENT, Permission.ADMIT_PATIENT,
            Permission.MANAGE_BEDS, Permission.EXPORT_REPORTS, Permission.SIMULATE)),
    DOCTOR("Doctor", EnumSet.of(
            Permission.REGISTER_PATIENT, Permission.REASSESS_PATIENT, Permission.ADMIT_PATIENT,
            Permission.MANAGE_BEDS, Permission.EXPORT_REPORTS, Permission.SIMULATE,
            Permission.DISCHARGE_PATIENT, Permission.TRANSFER_PATIENT, Permission.MANAGE_DOCTORS)),
    ADMIN("Administrator", EnumSet.allOf(Permission.class));

    private final String displayName;
    private final Set<Permission> permissions;

    Role(String displayName, EnumSet<Permission> permissions) {
        this.displayName = displayName;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public String getDisplayName() { return displayName; }

    public Set<Permission> getPermissions() { return permissions; }

    public boolean can(Permission permission) { return permissions.contains(permission); }

    @Override
    public String toString() { return displayName; }
}
