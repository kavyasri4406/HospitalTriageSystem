package com.hospital.service;

import com.hospital.model.Permission;
import com.hospital.model.Role;
import com.hospital.model.StaffUser;
import com.hospital.persistence.dao.StaffDAO;
import com.hospital.security.PasswordHasher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Login session and staff account management.
 * After {@value #MAX_FAILED_ATTEMPTS} wrong passwords an account is locked for {@link #LOCKOUT}.
 */
public class AuthService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(1);
    public static final int MIN_PASSWORD_LENGTH = 6;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,40}");

    private final StaffDAO staffDAO;
    private final Map<String, Integer> failedAttempts = new HashMap<>();
    private final Map<String, LocalDateTime> lockedUntil = new HashMap<>();
    private StaffUser currentUser;

    public AuthService(StaffDAO staffDAO) {
        this.staffDAO = staffDAO;
    }

    // ---- session ------------------------------------------------------------------

    public OperationResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return OperationResult.fail("Enter a username and password.");
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = lockedUntil.get(key);
        if (until != null && now.isBefore(until)) {
            long seconds = Math.max(1, Duration.between(now, until).toSeconds());
            return OperationResult.fail("Too many failed attempts. Try again in " + seconds + " s.");
        }
        Optional<StaffUser> found = staffDAO.findByUsername(key);
        // Always run the hash so response time does not reveal whether the username exists.
        String hash = found.map(StaffUser::getPasswordHash).orElse(DUMMY_HASH);
        boolean ok = PasswordHasher.verify(password, hash) && found.isPresent();
        if (!ok) {
            int attempts = failedAttempts.merge(key, 1, Integer::sum);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                lockedUntil.put(key, now.plus(LOCKOUT));
                failedAttempts.remove(key);
                return OperationResult.fail("Too many failed attempts. Account locked for " + LOCKOUT.toMinutes() + " minute(s).");
            }
            return OperationResult.fail("Invalid username or password.");
        }
        StaffUser user = found.get();
        if (!user.isActive()) {
            return OperationResult.fail("This account has been deactivated. Contact an administrator.");
        }
        failedAttempts.remove(key);
        lockedUntil.remove(key);
        user.setLastLogin(now);
        staffDAO.update(user);
        currentUser = user;
        return OperationResult.ok("Welcome, " + user.getFullName() + ".");
    }

    public void logout() {
        currentUser = null;
    }

    public StaffUser currentUser() {
        return currentUser;
    }

    /** Username recorded in audit logs: the logged-in user, or "system". */
    public String actorName() {
        return currentUser == null ? "system" : currentUser.getUsername();
    }

    public boolean hasPermission(Permission permission) {
        return currentUser != null && currentUser.can(permission);
    }

    // ---- account management ---------------------------------------------------------

    public List<StaffUser> listStaff() {
        return staffDAO.findAll();
    }

    public OperationResult createStaff(String username, String fullName, Role role, String password) {
        List<String> errors = new ArrayList<>();
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(user).matches()) {
            errors.add("Username must be 3-40 characters: lowercase letters, digits, '.', '_' or '-'.");
        } else if (staffDAO.findByUsername(user).isPresent()) {
            errors.add("Username '" + user + "' is already taken.");
        }
        if (fullName == null || fullName.isBlank() || fullName.trim().length() > 100) {
            errors.add("Full name is required (max 100 characters).");
        }
        if (role == null) errors.add("Choose a role.");
        String pwError = passwordProblem(password);
        if (pwError != null) errors.add(pwError);
        if (!errors.isEmpty()) return OperationResult.invalid(errors);

        StaffUser created = new StaffUser(0, user, fullName.trim(), role, PasswordHasher.hash(password),
                true, LocalDateTime.now(), null);
        staffDAO.insert(created);
        return OperationResult.ok("Created " + role.getDisplayName().toLowerCase(Locale.ROOT) + " account '" + user + "'.");
    }

    public OperationResult setActive(int staffId, boolean active) {
        StaffUser target = staffDAO.findById(staffId).orElse(null);
        if (target == null) return OperationResult.fail("Unknown staff account.");
        if (!active && currentUser != null && currentUser.getId() == staffId) {
            return OperationResult.fail("You cannot deactivate your own account.");
        }
        if (!active && target.getRole() == Role.ADMIN && activeAdminCount() <= 1) {
            return OperationResult.fail("At least one active administrator is required.");
        }
        target.setActive(active);
        staffDAO.update(target);
        return OperationResult.ok("'" + target.getUsername() + "' is now " + (active ? "active." : "deactivated."));
    }

    public OperationResult changeRole(int staffId, Role role) {
        StaffUser target = staffDAO.findById(staffId).orElse(null);
        if (target == null || role == null) return OperationResult.fail("Unknown staff account or role.");
        if (target.getRole() == Role.ADMIN && role != Role.ADMIN && target.isActive() && activeAdminCount() <= 1) {
            return OperationResult.fail("At least one active administrator is required.");
        }
        target.setRole(role);
        staffDAO.update(target);
        if (currentUser != null && currentUser.getId() == staffId) currentUser.setRole(role);
        return OperationResult.ok("'" + target.getUsername() + "' is now " + role.getDisplayName() + ".");
    }

    public OperationResult resetPassword(int staffId, String newPassword) {
        StaffUser target = staffDAO.findById(staffId).orElse(null);
        if (target == null) return OperationResult.fail("Unknown staff account.");
        String problem = passwordProblem(newPassword);
        if (problem != null) return OperationResult.fail(problem);
        target.setPasswordHash(PasswordHasher.hash(newPassword));
        staffDAO.update(target);
        return OperationResult.ok("Password reset for '" + target.getUsername() + "'.");
    }

    public OperationResult changeOwnPassword(String oldPassword, String newPassword) {
        if (currentUser == null) return OperationResult.fail("Not logged in.");
        StaffUser fresh = staffDAO.findById(currentUser.getId()).orElse(null);
        if (fresh == null || !PasswordHasher.verify(oldPassword, fresh.getPasswordHash())) {
            return OperationResult.fail("Current password is incorrect.");
        }
        String problem = passwordProblem(newPassword);
        if (problem != null) return OperationResult.fail(problem);
        fresh.setPasswordHash(PasswordHasher.hash(newPassword));
        staffDAO.update(fresh);
        currentUser.setPasswordHash(fresh.getPasswordHash());
        return OperationResult.ok("Your password has been changed.");
    }

    private long activeAdminCount() {
        return staffDAO.findAll().stream().filter(u -> u.isActive() && u.getRole() == Role.ADMIN).count();
    }

    private static String passwordProblem(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.";
        }
        if (password.length() > 128) return "Password must be at most 128 characters.";
        return null;
    }

    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-for-timing");
}
