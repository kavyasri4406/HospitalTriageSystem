package com.hospital.model;

import java.time.LocalDateTime;

/** A staff account that can log in to the system. The password is stored only as a salted hash. */
public class StaffUser {
    private int id;
    private final String username;
    private String fullName;
    private Role role;
    private String passwordHash;
    private boolean active;
    private final LocalDateTime createdAt;
    private LocalDateTime lastLogin;

    public StaffUser(int id, String username, String fullName, Role role, String passwordHash,
                     boolean active, LocalDateTime createdAt, LocalDateTime lastLogin) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.active = active;
        this.createdAt = createdAt;
        this.lastLogin = lastLogin;
    }

    public boolean can(Permission permission) {
        return active && role.can(permission);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }

    @Override
    public String toString() {
        return fullName + " (" + role.getDisplayName() + ")";
    }
}
