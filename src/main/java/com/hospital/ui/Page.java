package com.hospital.ui;

/** Screens reachable from the sidebar, in sidebar order (Ctrl+1..Ctrl+9 select the first nine). */
public enum Page {
    DASHBOARD("Dashboard", "Live overview of the emergency department"),
    INTAKE("Patient Intake", "Register a new arrival and run ESI triage"),
    QUEUE("Queue Board", "Waiting patients ordered by the priority heap"),
    BEDS("Bed Grid", "Ward capacity, admissions, transfers and discharges"),
    DOCTORS("Doctor Dispatch", "Roster, workload and duty status"),
    RECORDS("Patient Records", "Search every patient and review their history"),
    ANALYTICS("Analytics", "Throughput, wait times and utilisation"),
    ALERTS("Alerts", "Clinical and capacity notifications"),
    REPORTS("Reports", "Shift handover report, CSV exports and printing"),
    STAFF("Staff & Access", "Accounts, roles and permissions"),
    SETTINGS("Settings", "Automation, alerts, display and shortcuts");

    private final String title;
    private final String subtitle;

    Page(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    public String title() { return title; }
    public String subtitle() { return subtitle; }
}
