package com.hospital.web;

import com.hospital.model.Alert;
import com.hospital.model.AlertSeverity;
import com.hospital.model.AppSettings;
import com.hospital.model.Permission;
import com.hospital.model.Role;
import com.hospital.model.StaffUser;
import com.hospital.persistence.SchemaInitializer;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.service.ReportService;
import com.hospital.web.Web.Ctx;
import com.hospital.web.Web.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

import static com.hospital.web.Html.card;
import static com.hospital.web.Html.e;
import static com.hospital.web.Html.pill;
import static com.hospital.web.Html.postButton;

/** Login, alerts, reports and exports, staff accounts and settings. */
final class AdminPages {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private AdminPages() {}

    // =====================================================================
    // Login
    // =====================================================================

    static Response loginPage(Ctx c, String error, String username) {
        StringBuilder demo = new StringBuilder("<div class=\"demo\"><span class=\"muted\">Demo accounts</span><ul class=\"plain\">");
        for (String[] a : SchemaInitializer.DEMO_ACCOUNTS) {
            demo.append("<li><b>").append(e(a[0])).append("</b> / ").append(e(a[1])).append(" <span class=\"muted\">(")
                    .append(e(Role.valueOf(a[3]).getDisplayName())).append(")</span></li>");
        }
        demo.append("</ul></div>");
        String html = Html.head("Log in", 0) + "<body><div class=\"login-bg\"><form class=\"login\" method=\"post\" action=\"/login\">"
                + Html.hidden("csrf", c.csrf())
                + "<div><h1>ER Triage</h1><span class=\"muted\">Patient Triage &amp; Bed Allocator</span></div>"
                + (error == null ? "" : "<div class=\"flash err\">" + e(error) + "</div>")
                + "<label for=\"u\">Username</label><input type=\"text\" id=\"u\" name=\"username\" autocomplete=\"username\" required autofocus value=\""
                + e(username) + "\"><label for=\"p\">Password</label><input type=\"password\" id=\"p\" name=\"password\" autocomplete=\"current-password\" required>"
                + "<button class=\"btn btn-primary\">Log in</button>" + demo
                + "<span class=\"muted small\">Database: " + e(c.m().databaseDescription()) + " | Pure Java web server</span>"
                + "</form></div></body></html>";
        return Response.html(html);
    }

    // =====================================================================
    // Alerts
    // =====================================================================

    static Response alerts(Ctx c) {
        HospitalManager m = c.m();
        String filter = c.req().param("f");
        StringBuilder b = new StringBuilder("<div class=\"row\"><span>Show:</span>");
        for (String[] f : new String[][]{{"", "All"}, {"open", "Unacknowledged"}, {"CRITICAL", "Critical"}, {"WARNING", "Warning"}, {"INFO", "Info"}}) {
            b.append("<a class=\"btn btn-small ").append(f[0].equals(filter) ? "btn-primary" : "btn-secondary").append("\" href=\"/alerts")
                    .append(f[0].isEmpty() ? "" : "?f=" + f[0]).append("\">").append(f[1]).append("</a>");
        }
        b.append("<span class=\"spacer\"></span>").append(postButton("/ack-all", "Acknowledge All", "btn-primary", c.csrf(), true)).append("</div>");

        List<Alert> list = m.recentAlerts().stream().filter(a -> switch (filter) {
            case "" -> true;
            case "open" -> !a.isAcknowledged();
            case "CRITICAL", "WARNING", "INFO" -> a.getSeverity() == AlertSeverity.valueOf(filter);
            default -> true;
        }).toList();
        StringBuilder rows = new StringBuilder("<p class=\"muted\">").append(list.size()).append(" shown, ")
                .append(m.unacknowledgedAlertCount()).append(" unacknowledged</p>");
        if (!list.isEmpty()) {
            rows.append("<div class=\"table-wrap\"><table><tr><th>Severity</th><th>Category</th><th>Time</th><th>Message</th><th></th></tr>");
            for (Alert a : list) {
                rows.append("<tr><td>").append(ClinicalPages.severityPill(a)).append("</td><td class=\"small\">")
                        .append(e(a.getCategory().name().replace('_', ' '))).append("</td><td class=\"small\">")
                        .append(e(a.getCreatedAt().format(Html.DATE_TIME))).append("</td><td>")
                        .append(a.isAcknowledged() ? "<span class=\"muted\">" + e(a.getMessage()) + "</span>" : "<b>" + e(a.getMessage()) + "</b>")
                        .append("</td><td>").append(a.isAcknowledged() ? "<span class=\"muted small\">Acknowledged</span>"
                                : postButton("/ack", "Acknowledge", "btn-secondary btn-small", c.csrf(), true, "id", String.valueOf(a.getId())))
                        .append("</td></tr>");
            }
            rows.append("</table></div>");
        }
        b.append(card("Alerts", rows.toString()));
        return Response.html(Html.page(m, c.session(), "/alerts", "Alerts", "Clinical and capacity notifications", b.toString(), 30));
    }

    static Response ack(Ctx c) {
        c.m().acknowledgeAlert(c.req().intParam("id", -1));
        return Response.redirect("/alerts");
    }

    static Response ackAll(Ctx c) {
        c.m().acknowledgeAllAlerts();
        c.session().flash(true, "All alerts acknowledged.");
        return Response.redirect("/alerts");
    }

    // =====================================================================
    // Reports
    // =====================================================================

    static Response reports(Ctx c) {
        HospitalManager m = c.m();
        boolean can = m.hasPermission(Permission.EXPORT_REPORTS);
        String links = can
                ? "<div class=\"row\"><a class=\"btn btn-primary\" href=\"/export/report.txt\">Download report (.txt)</a>"
                + "<a class=\"btn btn-secondary\" href=\"/export/patients.csv\">Export patients CSV</a>"
                + "<a class=\"btn btn-secondary\" href=\"/export/activity.csv\">Export activity CSV</a>"
                + "<a class=\"btn btn-secondary\" href=\"/reports\">Refresh</a></div>"
                : "<p class=\"muted\">Your role cannot export reports.</p>";
        String report = new ReportService(m).shiftHandoverReport();
        String body = links + card("Shift handover report", "<pre class=\"report\">" + e(report) + "</pre>");
        return Response.html(Html.page(m, c.session(), "/reports", "Reports", "Shift handover report and CSV exports", body, 0));
    }

    /** Runs a ReportService export into a temp file and sends it to the browser as a download. */
    static Response export(Ctx c, String kind) {
        ReportService reports = new ReportService(c.m());
        String stamp = c.m().now().format(FILE_STAMP);
        Function<Path, OperationResult> writer;
        String name;
        String type;
        switch (kind) {
            case "patients.csv" -> { writer = reports::exportPatientsCsv; name = "patients-" + stamp + ".csv"; type = "text/csv; charset=utf-8"; }
            case "activity.csv" -> { writer = p -> reports.exportActivityCsv(p, 10_000); name = "activity-" + stamp + ".csv"; type = "text/csv; charset=utf-8"; }
            case "report.txt" -> { writer = reports::saveShiftReport; name = "shift-report-" + stamp + ".txt"; type = "text/plain; charset=utf-8"; }
            default -> { return Response.status(404, "Not found"); }
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("er-export-", ".tmp");
            OperationResult r = writer.apply(tmp);
            if (!r.success()) return c.done(r, "/reports");
            return Response.download(Files.readAllBytes(tmp), type, name);
        } catch (IOException ex) {
            return c.done(OperationResult.fail("Export failed: " + ex.getMessage()), "/reports");
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp file, best effort */ }
            }
        }
    }

    // =====================================================================
    // Staff and accounts
    // =====================================================================

    static Response staff(Ctx c) {
        HospitalManager m = c.m();
        StaffUser me = m.currentUser();
        String csrf = c.csrf();
        StringBuilder b = new StringBuilder();

        b.append(card("My account", "<p><b>" + e(me.getFullName()) + "</b> (" + e(me.getUsername()) + ") - " + e(me.getRole().getDisplayName())
                + (me.getLastLogin() == null ? "" : "<br><span class=\"muted\">Last login " + e(me.getLastLogin().format(Html.DATE_TIME)) + "</span>")
                + "</p><form method=\"post\" action=\"/account/password\"><div class=\"form\">" + Html.hidden("csrf", csrf)
                + "<label for=\"old\">Current password</label><input type=\"password\" id=\"old\" name=\"old\" required autocomplete=\"current-password\">"
                + "<label for=\"new1\">New password</label><input type=\"password\" id=\"new1\" name=\"new1\" required minlength=\"6\" autocomplete=\"new-password\">"
                + "<label for=\"new2\">Confirm new password</label><input type=\"password\" id=\"new2\" name=\"new2\" required minlength=\"6\" autocomplete=\"new-password\">"
                + "</div><p><button class=\"btn btn-primary\">Change My Password</button></p></form>"));

        if (m.hasPermission(Permission.MANAGE_STAFF)) {
            StringBuilder t = new StringBuilder("<div class=\"table-wrap\"><table><tr><th>Username</th><th>Name</th><th>Role</th><th>Status</th><th>Last login</th><th>Actions</th></tr>");
            for (StaffUser u : m.listStaff()) {
                String id = String.valueOf(u.getId());
                StringBuilder roles = new StringBuilder("<form method=\"post\" action=\"/staff/role\" class=\"inline\">")
                        .append(Html.hidden("csrf", csrf)).append(Html.hidden("id", id)).append("<select name=\"role\">");
                for (Role r : Role.values()) {
                    roles.append("<option value=\"").append(r.name()).append("\"").append(r == u.getRole() ? " selected" : "").append(">")
                            .append(e(r.getDisplayName())).append("</option>");
                }
                roles.append("</select> <button class=\"btn btn-secondary btn-small\">Set role</button></form>");
                t.append("<tr><td>").append(e(u.getUsername())).append("</td><td>").append(e(u.getFullName())).append("</td><td>")
                        .append(roles).append("</td><td>").append(u.isActive() ? pill("Active", "green") : pill("Deactivated", "red"))
                        .append("</td><td class=\"small\">").append(u.getLastLogin() == null ? "-" : e(u.getLastLogin().format(Html.DATE_TIME)))
                        .append("</td><td><div class=\"row\">")
                        .append(postButton("/staff/active", u.isActive() ? "Deactivate" : "Activate", "btn-secondary btn-small", csrf, true,
                                "id", id, "active", String.valueOf(!u.isActive())))
                        .append("<form method=\"post\" action=\"/staff/password\" class=\"inline\">").append(Html.hidden("csrf", csrf))
                        .append(Html.hidden("id", id)).append("<input type=\"password\" name=\"password\" placeholder=\"new password\" minlength=\"6\" required autocomplete=\"new-password\">")
                        .append(" <button class=\"btn btn-secondary btn-small\">Reset</button></form></div></td></tr>");
            }
            t.append("</table></div>");
            b.append(card("Staff accounts", t.toString()));

            StringBuilder add = new StringBuilder("<form method=\"post\" action=\"/staff/create\"><div class=\"form\">").append(Html.hidden("csrf", csrf))
                    .append("<label for=\"un\">Username</label><input type=\"text\" id=\"un\" name=\"username\" required maxlength=\"40\" placeholder=\"lowercase, e.g. dr.rao\">")
                    .append("<label for=\"fn\">Full name</label><input type=\"text\" id=\"fn\" name=\"fullName\" required maxlength=\"100\">")
                    .append("<label for=\"ro\">Role</label><select id=\"ro\" name=\"role\">");
            for (Role r : Role.values()) add.append("<option value=\"").append(r.name()).append("\">").append(e(r.getDisplayName())).append("</option>");
            add.append("</select><label for=\"pw\">Password</label><input type=\"password\" id=\"pw\" name=\"password\" required minlength=\"6\" autocomplete=\"new-password\">")
                    .append("</div><p><button class=\"btn btn-primary\">Add staff member</button></p></form>");
            b.append(card("Add staff member", add.toString()));
        } else {
            b.append(card("Staff accounts", "<p class=\"muted\">Only administrators can manage staff accounts.</p>"));
        }

        StringBuilder matrix = new StringBuilder("<div class=\"table-wrap\"><table><tr><th>Permission</th>");
        for (Role r : Role.values()) matrix.append("<th>").append(e(r.getDisplayName())).append("</th>");
        matrix.append("</tr>");
        for (Permission p : Permission.values()) {
            matrix.append("<tr><td>").append(e(p.getDescription())).append("</td>");
            for (Role r : Role.values()) matrix.append("<td>").append(r.can(p) ? pill("Yes", "green") : "-").append("</td>");
            matrix.append("</tr>");
        }
        b.append(card("Role permissions", matrix.append("</table></div>").toString()));
        return Response.html(Html.page(m, c.session(), "/staff", "Staff & Access", "Accounts, roles and permissions", b.toString(), 0));
    }

    static Response changeOwnPassword(Ctx c) {
        if (!c.req().param("new1").equals(c.req().param("new2"))) {
            return c.done(OperationResult.fail("The new passwords do not match."), "/staff");
        }
        return c.done(c.m().changeOwnPassword(c.req().param("old"), c.req().param("new1")), "/staff");
    }

    static Response createStaff(Ctx c) {
        Request r = c.req();
        Role role;
        try {
            role = Role.valueOf(r.param("role"));
        } catch (IllegalArgumentException ex) {
            role = null;
        }
        return c.done(c.m().createStaff(r.param("username"), r.param("fullName"), role, r.param("password")), "/staff");
    }

    static Response setActive(Ctx c) {
        return c.done(c.m().setStaffActive(c.req().intParam("id", -1), Boolean.parseBoolean(c.req().param("active"))), "/staff");
    }

    static Response setRole(Ctx c) {
        try {
            return c.done(c.m().changeStaffRole(c.req().intParam("id", -1), Role.valueOf(c.req().param("role"))), "/staff");
        } catch (IllegalArgumentException ex) {
            return c.done(OperationResult.fail("Unknown role."), "/staff");
        }
    }

    static Response resetPassword(Ctx c) {
        return c.done(c.m().resetStaffPassword(c.req().intParam("id", -1), c.req().param("password")), "/staff");
    }

    // =====================================================================
    // Settings
    // =====================================================================

    static Response settings(Ctx c) {
        HospitalManager m = c.m();
        AppSettings s = m.settings();
        boolean can = m.hasPermission(Permission.MANAGE_SETTINGS);
        String dis = can ? "" : " disabled";
        String form = "<form method=\"post\" action=\"/settings\"><div class=\"form\">" + Html.hidden("csrf", c.csrf())
                + "<span></span><label><input type=\"checkbox\" name=\"autoAdmit\"" + (s.autoAdmit() ? " checked" : "") + dis
                + "> Auto-admit: allocate free beds to waiting patients automatically</label>"
                + "<label for=\"th\">Occupancy alert threshold (%)</label><input type=\"number\" id=\"th\" name=\"threshold\" min=\"50\" max=\"100\" value=\""
                + Math.round(s.highOccupancyThreshold() * 100) + "\"" + dis + ">"
                + "<label for=\"cl\">Bed cleaning time (min)</label><input type=\"number\" id=\"cl\" name=\"cleaning\" min=\"0\" max=\"240\" value=\""
                + s.cleaningMinutes() + "\"" + dis + ">"
                + "<label for=\"rf\">Background refresh (s)</label><input type=\"number\" id=\"rf\" name=\"refresh\" min=\"2\" max=\"60\" value=\""
                + s.refreshSeconds() + "\"" + dis + ">"
                + "</div><p><button class=\"btn btn-primary\"" + dis + ">Save Settings</button></p>"
                + (can ? "" : "<p class=\"muted\">Only administrators can change settings.</p>") + "</form>";
        StaffUser u = m.currentUser();
        String info = "<p>Database: " + e(m.databaseDescription()) + "<br>Logged in: " + e(u.getFullName()) + " ("
                + e(u.getRole().getDisplayName()) + ")<br>Java " + e(System.getProperty("java.version")) + " | built-in web server (com.sun.net.httpserver)"
                + "<br>Beds: " + m.allBeds().size() + " | Doctors: " + m.allDoctors().size() + "</p>";
        String body = "<div class=\"grid g2\">" + card("Automation and alerts", form) + card("System information", info) + "</div>";
        return Response.html(Html.page(m, c.session(), "/settings", "Settings", "Automation and alert thresholds", body, 0));
    }

    static Response saveSettings(Ctx c) {
        HospitalManager m = c.m();
        Request r = c.req();
        AppSettings current = m.settings();
        AppSettings updated = new AppSettings(r.has("autoAdmit"), r.intParam("threshold", -1) / 100.0,
                current.darkMode(), r.intParam("refresh", -1), r.intParam("cleaning", -1));
        return c.done(m.updateSettings(updated), "/settings");
    }

    static byte[] css() {
        return Html.CSS.getBytes(StandardCharsets.UTF_8);
    }
}
