package com.hospital.model;

/** Actions that are restricted by staff role. Viewing data is allowed for every logged-in user. */
public enum Permission {
    REGISTER_PATIENT("Register patients"),
    REASSESS_PATIENT("Re-assess vitals"),
    ADMIT_PATIENT("Admit / allocate beds"),
    DISCHARGE_PATIENT("Discharge patients"),
    TRANSFER_PATIENT("Transfer patients between beds"),
    MANAGE_BEDS("Clean beds / maintenance"),
    MANAGE_DOCTORS("Change doctor duty status"),
    EXPORT_REPORTS("Export and print reports"),
    SIMULATE("Simulation tools (arrivals, clock)"),
    MANAGE_SETTINGS("Change system settings"),
    MANAGE_STAFF("Manage staff accounts"),
    RESET_DATA("Reset demo data");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}
