package com.hospital.web;

import com.hospital.model.Permission;
import com.hospital.model.StaffUser;
import com.hospital.model.TriageLevel;
import com.hospital.service.HospitalManager;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * HTML building blocks for the web version. All text from users or the database goes through
 * {@link #e(String)} (HTML escaping) to prevent cross-site scripting. The stylesheet is a Java text block.
 */
final class Html {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH);
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("EEE dd MMM, HH:mm", Locale.ENGLISH);

    private Html() {}

    /** Escapes text for HTML element content and quoted attribute values. */
    static String e(Object value) {
        if (value == null) return "";
        String s = value.toString();
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String minutes(long m) {
        if (m < 60) return m + " min";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    static String esi(TriageLevel level) {
        if (level == null) return "";
        return "<span class=\"esi esi-" + level.getEsi() + "\">ESI " + level.getEsi() + "</span>";
    }

    /** colour: red, green, amber, grey, blue. */
    static String pill(String text, String colour) {
        return "<span class=\"pill pill-" + colour + "\">" + e(text) + "</span>";
    }

    static String hidden(String name, Object value) {
        return "<input type=\"hidden\" name=\"" + e(name) + "\" value=\"" + e(value) + "\">";
    }

    /** A one-button POST form (all state changes are POST + CSRF token). */
    static String postButton(String action, String label, String cssClass, String csrf, boolean enabled, String... fields) {
        StringBuilder f = new StringBuilder("<form method=\"post\" action=\"").append(e(action)).append("\" class=\"inline\">")
                .append(hidden("csrf", csrf));
        for (int i = 0; i + 1 < fields.length; i += 2) f.append(hidden(fields[i], fields[i + 1]));
        f.append("<button class=\"btn ").append(cssClass).append("\"").append(enabled ? "" : " disabled").append(">")
                .append(e(label)).append("</button></form>");
        return f.toString();
    }

    static String card(String title, String body) {
        return "<section class=\"card\">" + (title == null ? "" : "<h2>" + e(title) + "</h2>") + body + "</section>";
    }

    // ---- page layout ----------------------------------------------------------------------

    record NavItem(String path, String label) {}

    static final NavItem[] NAV = {
            new NavItem("/", "Dashboard"),
            new NavItem("/intake", "Patient Intake"),
            new NavItem("/queue", "Queue Board"),
            new NavItem("/beds", "Bed Grid"),
            new NavItem("/doctors", "Doctor Dispatch"),
            new NavItem("/records", "Patient Records"),
            new NavItem("/alerts", "Alerts"),
            new NavItem("/reports", "Reports"),
            new NavItem("/staff", "Staff & Access"),
            new NavItem("/settings", "Settings"),
    };

    /**
     * Full page with sidebar navigation.
     *
     * @param refreshSeconds 0 for none, otherwise the page reloads itself (live boards)
     */
    static String page(HospitalManager m, SessionStore.Session session, String activePath, String title, String subtitle,
                       String body, int refreshSeconds) {
        StaffUser user = m.currentUser();
        StringBuilder nav = new StringBuilder();
        long unacked = m.unacknowledgedAlertCount();
        for (NavItem item : NAV) {
            boolean active = item.path().equals(activePath);
            nav.append("<a href=\"").append(item.path()).append("\"").append(active ? " class=\"active\"" : "").append(">")
                    .append(e(item.label()));
            if (item.path().equals("/alerts") && unacked > 0) {
                nav.append(" <span class=\"badge\">").append(unacked > 99 ? "99+" : unacked).append("</span>");
            }
            nav.append("</a>");
        }
        String flash = "";
        if (session.flash != null) {
            flash = "<div class=\"flash " + (session.flashOk ? "ok" : "err") + "\">" + e(session.flash) + "</div>";
            session.flash = null;
        }
        long offset = m.clockOffset().toMinutes();
        String clock = (offset > 0 ? pill("simulated +" + minutes(offset), "amber") + " " : "")
                + "<span class=\"clock\">" + e(m.now().format(CLOCK)) + "</span>";
        String account = user == null ? "" : "<div class=\"account\"><strong>" + e(user.getFullName()) + "</strong><span>"
                + e(user.getRole().getDisplayName()) + " | " + e(user.getUsername()) + "</span>"
                + postButton("/logout", "Log out", "btn-secondary btn-small", session.csrf, true) + "</div>";
        return head(title, refreshSeconds)
                + "<body><div class=\"shell\"><nav class=\"sidebar\"><div class=\"brand\"><b>ER Triage</b>"
                + "<span>Patient Triage &amp; Bed Allocator</span></div><div class=\"links\">" + nav + "</div>" + account
                + "<div class=\"foot\">Database: " + e(m.databaseDescription()) + "<br>Pure Java web server</div></nav>"
                + "<main><header class=\"topbar\"><div><h1>" + e(title) + "</h1><p>" + e(subtitle) + "</p></div><div>" + clock
                + "</div></header><div class=\"content\">" + flash + body + "</div></main></div></body></html>";
    }

    static String head(String title, int refreshSeconds) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + (refreshSeconds > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">" : "")
                + "<title>" + e(title) + " - ER Triage</title><link rel=\"stylesheet\" href=\"/style.css\"></head>";
    }

    static boolean can(HospitalManager m, Permission p) {
        return m.hasPermission(p);
    }

    static final String CSS = """
            *{box-sizing:border-box}
            body{margin:0;font-family:"Segoe UI",Roboto,Helvetica,Arial,sans-serif;font-size:14px;background:#f1f5f9;color:#0f172a}
            a{color:#2563eb;text-decoration:none} a:hover{text-decoration:underline}
            .shell{display:flex;min-height:100vh}
            .sidebar{width:230px;flex:none;background:#0f2a44;color:#c9d8e8;padding:20px 12px;display:flex;flex-direction:column;gap:4px}
            .brand{padding:0 6px 18px}.brand b{display:block;color:#fff;font-size:18px}.brand span{font-size:11px;color:#8fb3d9}
            .links{display:flex;flex-direction:column;gap:2px}
            .links a{color:#c9d8e8;padding:9px 12px;border-radius:8px;display:block}
            .links a:hover{background:#1b3d61;color:#fff;text-decoration:none}
            .links a.active{background:#2563eb;color:#fff;font-weight:600}
            .badge{background:#dc2626;color:#fff;border-radius:999px;padding:1px 7px;font-size:11px;font-weight:700}
            .account{margin-top:auto;padding:12px 6px;display:flex;flex-direction:column;gap:4px}
            .account strong{color:#fff}.account span{font-size:11px;color:#8fb3d9}
            .foot{font-size:11px;color:#8fb3d9;padding:0 6px}
            main{flex:1;min-width:0;display:flex;flex-direction:column}
            .topbar{background:#fff;border-bottom:1px solid #e2e8f0;padding:12px 22px;display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
            .topbar h1{margin:0;font-size:20px}.topbar p{margin:2px 0 0;color:#64748b}
            .clock{font-weight:700}
            .content{padding:20px 22px;display:flex;flex-direction:column;gap:16px}
            .card{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:16px;box-shadow:0 2px 10px rgba(15,23,42,.05);min-width:0}
            .card h2{margin:0 0 12px;font-size:15px}
            .grid{display:grid;gap:16px}.g2{grid-template-columns:3fr 2fr}.g2e{grid-template-columns:1fr 1fr}
            .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px}
            .kpi{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:14px 16px;border-top:4px solid #2563eb}
            .kpi b{display:block;font-size:26px}.kpi span{color:#64748b;font-size:12px}
            .k-red{border-top-color:#d32f2f}.k-amber{border-top-color:#f57c00}.k-green{border-top-color:#16a34a}.k-violet{border-top-color:#7c3aed}.k-cyan{border-top-color:#0891b2}
            .row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
            .spacer{flex:1}
            .muted{color:#64748b}.small{font-size:12px}
            form.inline{display:inline}
            .btn{border:0;border-radius:8px;padding:8px 14px;font:inherit;font-weight:600;cursor:pointer;display:inline-block}
            .btn:disabled{opacity:.45;cursor:not-allowed}
            .btn-primary{background:#2563eb;color:#fff}.btn-success{background:#16a34a;color:#fff}.btn-danger{background:#dc2626;color:#fff}
            .btn-warning{background:#f59e0b;color:#1f2937}.btn-secondary{background:#e2e8f0;color:#0f172a}
            .btn-small{padding:4px 10px;font-size:12px}
            a.btn:hover{text-decoration:none}
            .esi{border-radius:999px;padding:2px 9px;font-size:11px;font-weight:700;color:#fff;white-space:nowrap}
            .esi-1{background:#d32f2f}.esi-2{background:#f57c00}.esi-3{background:#fbc02d;color:#1f2937}.esi-4{background:#388e3c}.esi-5{background:#1976d2}
            .pill{border-radius:999px;padding:2px 9px;font-size:11px;font-weight:700;white-space:nowrap}
            .pill-red{background:#fee2e2;color:#b91c1c}.pill-green{background:#dcfce7;color:#15803d}.pill-amber{background:#fef3c7;color:#b45309}
            .pill-grey{background:#e2e8f0;color:#334155}.pill-blue{background:#dbeafe;color:#1d4ed8}
            .flash{padding:10px 14px;border-radius:8px;font-weight:600}.flash.ok{background:#dcfce7;color:#14532d}.flash.err{background:#fee2e2;color:#7f1d1d}
            .table-wrap{overflow-x:auto}
            table{border-collapse:collapse;width:100%}
            th,td{text-align:left;padding:7px 8px;border-bottom:1px solid #e2e8f0;vertical-align:top}
            th{background:#f8fafc;font-size:12px;color:#334155;white-space:nowrap}
            tr.overdue td{background:#fff1f2}tr.sel td{background:#dbeafe}
            progress{width:100%;height:10px;accent-color:#16a34a}progress.hot{accent-color:#dc2626}progress.warm{accent-color:#f59e0b}
            .tiles{display:flex;flex-wrap:wrap;gap:12px}
            .tile{width:200px;border:2px solid;border-radius:10px;padding:10px;display:flex;flex-direction:column;gap:5px}
            .t-AVAILABLE{background:#f0fdf4;border-color:#22c55e}.t-OCCUPIED{background:#fef2f2;border-color:#ef4444}
            .t-CLEANING{background:#fffbeb;border-color:#f59e0b}.t-MAINTENANCE{background:#f1f5f9;border-color:#94a3b8}
            .tile b{font-size:15px}
            .form{display:grid;grid-template-columns:200px 1fr;gap:10px 14px;align-items:center;max-width:720px}
            label{font-weight:600;color:#334155}
            input[type=text],input[type=password],input[type=number],input[type=search],select{font:inherit;padding:7px 9px;border:1px solid #cbd5e1;border-radius:7px;background:#fff;max-width:100%}
            input[type=number]{width:110px}
            .errors{color:#b91c1c;margin:0;padding-left:18px}
            pre.report{font-family:Consolas,"Courier New",monospace;font-size:12px;background:#0f172a;color:#e2e8f0;padding:16px;border-radius:10px;overflow-x:auto;margin:0}
            ul.plain{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:6px}
            .login-bg{min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#0f2a44,#1e4a7a);padding:16px}
            .login{background:#fff;border-radius:14px;padding:30px;width:100%;max-width:400px;display:flex;flex-direction:column;gap:12px}
            .login h1{margin:0;font-size:22px}.login input{width:100%}
            .demo{border:1px dashed #cbd5e1;border-radius:8px;padding:10px;font-size:13px}
            @media (max-width:900px){
              .shell{flex-direction:column}.sidebar{width:auto;padding:12px}.links{flex-direction:row;overflow-x:auto}
              .links a{white-space:nowrap}.account{flex-direction:row;flex-wrap:wrap;align-items:center;margin-top:6px}.foot{display:none}
              .g2,.g2e{grid-template-columns:1fr}.form{grid-template-columns:1fr}.content{padding:14px}
            }
            """;
}
