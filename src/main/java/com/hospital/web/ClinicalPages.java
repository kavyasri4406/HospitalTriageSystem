package com.hospital.web;

import com.hospital.algorithms.WaitTimePredictor.Prediction;
import com.hospital.model.Alert;
import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.model.PatientIntakeForm;
import com.hospital.model.PatientStatus;
import com.hospital.model.Permission;
import com.hospital.model.Vitals;
import com.hospital.model.VitalsRecord;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.service.AnalyticsSnapshot;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.web.Web.Ctx;
import com.hospital.web.Web.Response;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.hospital.web.Html.card;
import static com.hospital.web.Html.e;
import static com.hospital.web.Html.esi;
import static com.hospital.web.Html.minutes;
import static com.hospital.web.Html.pill;
import static com.hospital.web.Html.postButton;

/** Dashboard, intake, queue, re-assessment, beds, transfers, doctors and patient records. */
final class ClinicalPages {

    private static final String[] COMPLAINTS = {
            "Chest pain", "Shortness of breath", "Abdominal pain", "Head injury", "Fever", "Fall", "Laceration",
            "Seizure", "Stroke symptoms", "Allergic reaction / anaphylaxis", "Overdose", "Back pain", "Sore throat",
            "Vomiting and diarrhoea", "Fracture", "Burn", "Dizziness"};

    private ClinicalPages() {}

    // =====================================================================
    // Dashboard
    // =====================================================================

    static Response dashboard(Ctx c) {
        HospitalManager m = c.m();
        AnalyticsSnapshot s = m.analytics();
        LocalDateTime now = m.now();
        StringBuilder b = new StringBuilder();

        b.append("<div class=\"kpis\">")
                .append(kpi(s.waitingCount(), "Patients waiting", ""))
                .append(kpi(s.criticalWaitingCount(), "Critical (ESI 1-2) waiting", "k-red"))
                .append(kpi(s.overdueCount(), "Over target wait", "k-amber"))
                .append(kpi(s.bedsAvailable() + " / " + s.bedsTotal(), "Beds available", "k-green"))
                .append(kpi(s.doctorsAvailable() + " / " + s.doctorsOnDuty(), "Doctors available", "k-violet"))
                .append(kpi(minutes(Math.round(s.averageWaitMinutes())), "Average wait", "k-cyan"))
                .append("</div>");

        String csrf = c.csrf();
        b.append("<div class=\"row\">")
                .append(postButton("/admit-next", "Admit Next Patient", "btn-primary", csrf, m.hasPermission(Permission.ADMIT_PATIENT)))
                .append(postButton("/auto-allocate", "Auto-Allocate All Beds", "btn-success", csrf, m.hasPermission(Permission.ADMIT_PATIENT)))
                .append("<a class=\"btn btn-secondary\" href=\"/intake\">+ Register Patient</a>")
                .append(postButton("/simulate", "Simulate 5 Arrivals", "btn-secondary", csrf, m.hasPermission(Permission.SIMULATE)))
                .append(postButton("/advance", "Advance Clock +15 min", "btn-warning", csrf, m.hasPermission(Permission.SIMULATE)))
                .append("<span class=\"spacer\"></span>")
                .append(postButton("/reset", "Reset Demo Data", "btn-danger", csrf, m.hasPermission(Permission.RESET_DATA)))
                .append("</div>");

        StringBuilder next = new StringBuilder();
        List<Patient> queue = m.waitingQueue();
        if (queue.isEmpty()) {
            next.append("<p class=\"muted\">No patients waiting. Register a patient or simulate arrivals.</p>");
        } else {
            next.append("<div class=\"table-wrap\"><table><tr><th>#</th><th>ESI</th><th>Patient</th><th>Waited</th><th>Priority</th></tr>");
            int rank = 1;
            for (Patient p : queue.subList(0, Math.min(8, queue.size()))) {
                next.append("<tr><td>").append(rank++).append("</td><td>").append(esi(p.getTriageLevel()))
                        .append("</td><td><a href=\"/queue?id=").append(p.getId()).append("\">").append(e(p.getFullName()))
                        .append("</a><br><span class=\"muted small\">").append(e(p.getChiefComplaint())).append("</span></td><td>")
                        .append(p.isOverdue(now) ? pill(minutes(p.waitingMinutes(now)) + " overdue", "red") : pill(minutes(p.waitingMinutes(now)), "grey"))
                        .append("</td><td><b>").append(String.format("%.1f", p.getPriorityScore())).append("</b></td></tr>");
            }
            next.append("</table></div>");
            if (queue.size() > 8) next.append("<p><a href=\"/queue\">View all ").append(queue.size()).append(" waiting</a></p>");
        }

        StringBuilder occ = new StringBuilder("<ul class=\"plain\">");
        for (BedType t : BedType.values()) {
            int[] counts = s.occupancyByType().get(t);
            occ.append("<li><div class=\"row\"><b>").append(e(t.getDisplayName())).append("</b><span class=\"spacer\"></span><span class=\"muted small\">")
                    .append(counts[0]).append(" / ").append(counts[1]).append(" occupied</span></div>")
                    .append(progress(counts[0], counts[1])).append("</li>");
        }
        occ.append("</ul><p class=\"muted small\">").append(String.format("Overall occupancy %.0f%%", s.occupancyRate() * 100))
                .append(" | ").append(s.bedsByStatus().get(BedStatus.CLEANING)).append(" cleaning | ")
                .append(s.bedsByStatus().get(BedStatus.MAINTENANCE)).append(" maintenance</p>");

        StringBuilder alerts = new StringBuilder("<ul class=\"plain\">");
        List<Alert> recent = m.recentAlerts();
        if (recent.isEmpty()) alerts.append("<li class=\"muted\">No alerts.</li>");
        for (Alert a : recent.subList(0, Math.min(6, recent.size()))) {
            alerts.append("<li>").append(severityPill(a)).append(" <span class=\"muted small\">").append(e(a.getCreatedAt().format(Html.TIME)))
                    .append("</span> ").append(a.isAcknowledged() ? "<span class=\"muted\">" : "<b>").append(e(a.getMessage()))
                    .append(a.isAcknowledged() ? "</span>" : "</b>").append("</li>");
        }
        alerts.append("</ul><p><a href=\"/alerts\">Open alerts</a></p>");

        StringBuilder activity = new StringBuilder("<ul class=\"plain\">");
        List<LogEntry> log = m.recentActivity(8);
        if (log.isEmpty()) activity.append("<li class=\"muted\">No activity yet.</li>");
        for (LogEntry l : log) {
            String who = m.findPatientRecord(l.patientId()).map(Patient::getFullName).orElse("Patient #" + l.patientId());
            activity.append("<li><span class=\"muted small\">").append(e(l.createdAt().format(Html.TIME))).append("</span> ")
                    .append(actionPill(l.action())).append(" ").append(e(who)).append(l.bedId() == null ? "" : " &rarr; " + e(l.bedId()))
                    .append(l.performedBy() == null ? "" : " <span class=\"muted small\">by " + e(l.performedBy()) + "</span>").append("</li>");
        }
        activity.append("</ul>");

        b.append("<div class=\"grid g2\">").append(card("Next in queue (max-heap order)", next.toString()))
                .append(card("Bed occupancy by ward", occ.toString())).append("</div>")
                .append("<div class=\"grid g2\">").append(card("Recent activity (audit log)", activity.toString()))
                .append(card("Latest alerts", alerts.toString())).append("</div>");
        return Response.html(Html.page(m, c.session(), "/", "Dashboard", "Live overview of the emergency department", b.toString(), 20));
    }

    private static String kpi(Object value, String label, String colour) {
        return "<div class=\"kpi " + colour + "\"><b>" + e(value) + "</b><span>" + e(label) + "</span></div>";
    }

    static String progress(int value, int max) {
        double ratio = max == 0 ? 0 : (double) value / max;
        String cls = ratio >= 0.9 ? " class=\"hot\"" : ratio >= 0.7 ? " class=\"warm\"" : "";
        return "<progress" + cls + " value=\"" + value + "\" max=\"" + Math.max(1, max) + "\"></progress>";
    }

    static String severityPill(Alert a) {
        return switch (a.getSeverity()) {
            case CRITICAL -> pill("CRITICAL", "red");
            case WARNING -> pill("WARNING", "amber");
            case INFO -> pill("INFO", "blue");
        };
    }

    static String actionPill(String action) {
        String colour = switch (action) {
            case "ADMITTED" -> "green";
            case "DISCHARGED", "TRANSFERRED" -> "blue";
            case "REASSESSED" -> "amber";
            case "LEFT_WITHOUT_BEING_SEEN" -> "red";
            default -> "grey";
        };
        return pill(action.replace('_', ' '), colour);
    }

    // =====================================================================
    // Dashboard / queue actions
    // =====================================================================

    static Response admitNext(Ctx c) { return c.done(c.m().admitNext(), back(c, "/queue")); }

    static Response autoAllocate(Ctx c) { return c.done(c.m().autoAllocate(), back(c, "/beds")); }

    static Response admitOne(Ctx c) { return c.done(c.m().admitPatient(c.req().intParam("id", -1)), "/beds"); }

    static Response lwbs(Ctx c) { return c.done(c.m().markLeftWithoutBeingSeen(c.req().intParam("id", -1)), "/queue"); }

    static Response simulate(Ctx c) {
        List<OperationResult> results = c.m().simulateArrivals(5);
        OperationResult first = results.isEmpty() ? OperationResult.fail("Nothing simulated.") : results.get(0);
        return c.done(first.success() ? OperationResult.ok(results.size() + " simulated patients arrived and were triaged.") : first, "/");
    }

    static Response advance(Ctx c) { return c.done(c.m().advanceClock(15), "/"); }

    static Response reset(Ctx c) { return c.done(c.m().resetDemoData(), "/"); }

    /** Return to the page the form came from when it is one of ours, else to {@code fallback}. */
    private static String back(Ctx c, String fallback) {
        String from = c.req().param("back");
        return from.startsWith("/") && !from.startsWith("//") ? from : fallback;
    }

    // =====================================================================
    // Intake
    // =====================================================================

    static Response intake(Ctx c) {
        PatientIntakeForm f = c.req().has("sample") ? c.m().randomIntakeForm()
                : new PatientIntakeForm("", 35, "Female", "", false, 80, 120, 80, 16, 98, 37.0, 0, 15, 1);
        return intakePage(c, f, List.of());
    }

    static Response registerPatient(Ctx c) {
        Request r = c.req();
        PatientIntakeForm f = new PatientIntakeForm(r.param("name"), r.intParam("age", -1), r.param("gender"),
                r.param("complaint"), r.has("trauma"), r.intParam("hr", -1), r.intParam("sbp", -1), r.intParam("dbp", -1),
                r.intParam("rr", -1), r.intParam("spo2", -1), r.doubleParam("temp", Double.NaN), r.intParam("pain", -1),
                r.intParam("gcs", -1), r.intParam("resources", -1));
        OperationResult result = c.m().registerPatient(f);
        if (!result.success()) {
            List<String> errors = result.errors().isEmpty() ? List.of(result.message()) : result.errors();
            return intakePage(c, f, errors);
        }
        Patient p = result.patient();
        String why = result.assessment() == null ? "" : " " + String.join("; ", result.assessment().reasons());
        c.session().flash(true, result.message() + "." + why);
        return Response.redirect("/queue?id=" + p.getId());
    }

    private static Response intakePage(Ctx c, PatientIntakeForm f, List<String> errors) {
        HospitalManager m = c.m();
        StringBuilder b = new StringBuilder();
        if (!errors.isEmpty()) {
            b.append("<div class=\"flash err\">Please correct the following:<ul class=\"errors\">");
            for (String err : errors) b.append("<li>").append(e(err)).append("</li>");
            b.append("</ul></div>");
        }
        StringBuilder form = new StringBuilder("<form method=\"post\" action=\"/intake\"><div class=\"form\">")
                .append(Html.hidden("csrf", c.csrf()))
                .append("<label for=\"name\">Full name</label><input type=\"text\" id=\"name\" name=\"name\" maxlength=\"100\" required value=\"")
                .append(e(f.fullName())).append("\" placeholder=\"e.g. Priya Sharma\">")
                .append(num("Age (years)", "age", f.age(), 0, 120, "1"))
                .append("<label for=\"gender\">Gender</label><select id=\"gender\" name=\"gender\">");
        for (String g : new String[]{"Male", "Female", "Other"}) {
            form.append("<option").append(g.equals(f.gender()) ? " selected" : "").append(">").append(g).append("</option>");
        }
        form.append("</select><label for=\"complaint\">Chief complaint</label><input type=\"text\" id=\"complaint\" name=\"complaint\" list=\"complaints\" maxlength=\"255\" required value=\"")
                .append(e(f.chiefComplaint())).append("\"><datalist id=\"complaints\">");
        for (String cpl : COMPLAINTS) form.append("<option value=\"").append(e(cpl)).append("\">");
        form.append("</datalist><span></span><label><input type=\"checkbox\" name=\"trauma\"").append(f.trauma() ? " checked" : "")
                .append("> Traumatic injury (accident, fall, assault)</label>")
                .append(num("Heart rate (bpm)", "hr", f.heartRate(), 20, 250, "1"))
                .append(num("Systolic BP (mmHg)", "sbp", f.systolicBp(), 40, 260, "1"))
                .append(num("Diastolic BP (mmHg)", "dbp", f.diastolicBp(), 20, 160, "1"))
                .append(num("Respiratory rate (/min)", "rr", f.respiratoryRate(), 4, 70, "1"))
                .append(num("SpO2 (%)", "spo2", f.spo2(), 50, 100, "1"))
                .append(num("Temperature (°C)", "temp", f.temperature(), 30, 44, "0.1"))
                .append(num("Pain score (0-10)", "pain", f.painScore(), 0, 10, "1"))
                .append(num("GCS (3-15)", "gcs", f.gcs(), 3, 15, "1"))
                .append(num("Expected resources (0-5)", "resources", f.expectedResources(), 0, 5, "1"))
                .append("</div><p class=\"row\"><button class=\"btn btn-primary\"")
                .append(m.hasPermission(Permission.REGISTER_PATIENT) ? "" : " disabled")
                .append(">Register &amp; Triage Patient</button><a class=\"btn btn-secondary\" href=\"/intake?sample=1\">Fill Sample Patient</a>")
                .append("<a class=\"btn btn-secondary\" href=\"/intake\">Clear</a></p></form>");

        StringBuilder legend = new StringBuilder("<ul class=\"plain\">");
        for (var level : com.hospital.model.TriageLevel.values()) {
            legend.append("<li>").append(esi(level)).append(" <b>").append(e(level.getLabel())).append("</b> <span class=\"muted\">")
                    .append(level.getTargetWaitMinutes() == 0 ? "immediate" : "within " + level.getTargetWaitMinutes() + " min")
                    .append("</span></li>");
        }
        legend.append("</ul><p class=\"muted small\">GCS 15 = fully alert, 8 or less = unresponsive. Resources: labs, imaging, IV fluids, specialist consult, procedures.</p>");

        b.append("<div class=\"grid g2\">").append(card("Patient details and vital signs", form.toString()))
                .append(card("ESI levels and target wait", legend.toString())).append("</div>");
        return Response.html(Html.page(m, c.session(), "/intake", "Patient Intake", "Register a new arrival and run ESI triage", b.toString(), 0));
    }

    private static String num(String label, String name, Object value, double min, double max, String step) {
        String v = value instanceof Double d ? (Double.isNaN(d) ? "" : String.valueOf(d)) : String.valueOf(value);
        if ("-1".equals(v)) v = "";
        return "<label for=\"" + name + "\">" + e(label) + "</label><input type=\"number\" id=\"" + name + "\" name=\"" + name
                + "\" min=\"" + fmt(min) + "\" max=\"" + fmt(max) + "\" step=\"" + step + "\" required value=\"" + e(v) + "\">";
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // =====================================================================
    // Queue board
    // =====================================================================

    static Response queue(Ctx c) {
        HospitalManager m = c.m();
        LocalDateTime now = m.now();
        List<Patient> queue = m.waitingQueue();
        Map<Integer, Prediction> predictions = m.predictWaits();
        int selectedId = c.req().intParam("id", -1);
        String csrf = c.csrf();
        boolean canAdmit = m.hasPermission(Permission.ADMIT_PATIENT);

        StringBuilder b = new StringBuilder("<div class=\"row\">")
                .append(postButton("/admit-next", "Admit Next (top of heap)", "btn-primary", csrf, canAdmit, "back", "/queue"))
                .append(postButton("/auto-allocate", "Auto-Allocate", "btn-success", csrf, canAdmit, "back", "/queue"))
                .append("<span class=\"muted\">").append(queue.size()).append(" waiting | ")
                .append(queue.stream().filter(p -> p.getTriageLevel().isCritical()).count()).append(" critical | ")
                .append(queue.stream().filter(p -> p.isOverdue(now)).count()).append(" overdue</span></div>");

        StringBuilder table = new StringBuilder();
        if (queue.isEmpty()) {
            table.append("<p class=\"muted\">The waiting room is empty.</p>");
        } else {
            table.append("<div class=\"table-wrap\"><table><tr><th>#</th><th>ESI</th><th>Name</th><th>Age/Sex</th><th>Complaint</th>")
                    .append("<th>Severity</th><th>Aging +</th><th>Priority</th><th>Waiting</th><th>Target</th><th>Est. bed</th><th>Status</th></tr>");
            int rank = 1;
            for (Patient p : queue) {
                String cls = p.getId() == selectedId ? " class=\"sel\"" : p.isOverdue(now) ? " class=\"overdue\"" : "";
                table.append("<tr").append(cls).append("><td>").append(rank++).append("</td><td>").append(esi(p.getTriageLevel()))
                        .append("</td><td><a href=\"/queue?id=").append(p.getId()).append("\">").append(e(p.getFullName())).append("</a></td><td>")
                        .append(p.getAge()).append(' ').append(e(p.getGender().isEmpty() ? "" : p.getGender().substring(0, 1))).append("</td><td>")
                        .append(e(p.getChiefComplaint())).append("</td><td>").append(String.format("%.1f", p.getSeverityScore()))
                        .append("</td><td>").append(String.format("%.1f", p.getAgingBonus())).append("</td><td><b>")
                        .append(String.format("%.1f", p.getPriorityScore())).append("</b></td><td>").append(minutes(p.waitingMinutes(now)))
                        .append("</td><td>").append(p.getTriageLevel().getTargetWaitMinutes()).append(" min</td><td>")
                        .append(e(estimate(predictions.get(p.getId())))).append("</td><td>")
                        .append(p.isOverdue(now) ? pill("OVERDUE", "red") : pill("On time", "green")).append("</td></tr>");
            }
            table.append("</table></div>");
        }

        Patient sel = selectedId > 0 ? m.findPatient(selectedId) : null;
        String details;
        if (sel == null || sel.getStatus() != PatientStatus.WAITING) {
            details = "<p class=\"muted\">Select a patient to see vitals, scoring and the ESI reasoning.</p>"
                    + (sel != null ? "<p><a href=\"/records?id=" + sel.getId() + "\">Open patient record</a></p>" : "");
        } else {
            Vitals v = sel.getVitals();
            Prediction pr = predictions.get(sel.getId());
            StringBuilder d = new StringBuilder("<p><b>").append(e(sel.getFullName())).append("</b><br><span class=\"muted\">")
                    .append(e(sel.getMrn())).append(" | ").append(sel.getAge()).append(" y, ").append(e(sel.getGender())).append(" | ")
                    .append(e(sel.getCategory().toLowerCase())).append("</span></p><p>").append(esi(sel.getTriageLevel())).append(" ")
                    .append(e(sel.getTriageLevel().getLabel())).append("</p><p><b>").append(e(sel.getChiefComplaint())).append("</b>")
                    .append(sel.isTrauma() ? " (trauma)" : "").append("<br><span class=\"muted\">Arrived ")
                    .append(e(sel.getIntakeTime().format(Html.DATE_TIME))).append(", waiting ").append(minutes(sel.waitingMinutes(now)))
                    .append("</span></p>").append(vitalsBlock(v, sel.getExpectedResources()))
                    .append("<p>Severity ").append(String.format("%.1f", sel.getSeverityScore())).append(" + aging ")
                    .append(String.format("%.1f", sel.getAgingBonus())).append(" = <b>priority ").append(String.format("%.1f", sel.getPriorityScore()))
                    .append("</b><br>Needs bed: ").append(e(m.idealBedType(sel).getDisplayName()))
                    .append(pr == null ? "" : "<br>Predicted bed: " + e(estimate(pr)) + (pr.bedType() == null ? "" : " (" + e(pr.bedType().getDisplayName()) + ")"))
                    .append("</p><p><b>ESI reasoning</b></p><ul>");
            for (String reason : m.triageReasons(sel.getId())) d.append("<li>").append(e(reason)).append("</li>");
            d.append("</ul><div class=\"row\">")
                    .append(postButton("/admit", "Admit this patient", "btn-success", csrf, canAdmit, "id", String.valueOf(sel.getId())))
                    .append(m.hasPermission(Permission.REASSESS_PATIENT)
                            ? "<a class=\"btn btn-warning\" href=\"/reassess?id=" + sel.getId() + "\">Re-assess vitals</a>" : "")
                    .append(postButton("/lwbs", "Left without being seen", "btn-danger", csrf, canAdmit, "id", String.valueOf(sel.getId())))
                    .append("</div>");
            details = d.toString();
        }
        b.append("<div class=\"grid g2\">").append(card("Waiting patients (priority order)", table.toString()))
                .append(card("Patient details", details)).append("</div>");
        return Response.html(Html.page(m, c.session(), "/queue", "Queue Board", "Waiting patients ordered by the priority heap", b.toString(), 30));
    }

    static String estimate(Prediction p) {
        if (p == null) return "-";
        if (p.noBedPossible()) return "no bed";
        long mins = p.minutesUntilBed();
        return mins <= 0 ? "now" : "~" + minutes(mins);
    }

    private static String vitalsBlock(Vitals v, int resources) {
        return "<p>HR " + v.heartRate() + " bpm | BP " + e(v.bloodPressure()) + " mmHg | RR " + v.respiratoryRate()
                + "/min<br>SpO2 " + v.spo2() + "% | Temp " + String.format("%.1f", v.temperature()) + " °C | Pain "
                + v.painScore() + "/10 | GCS " + v.gcs() + "<br>Expected resources: " + resources + "</p>";
    }

    // =====================================================================
    // Re-assessment
    // =====================================================================

    static Response reassessForm(Ctx c) {
        HospitalManager m = c.m();
        Patient p = m.findPatient(c.req().intParam("id", -1));
        if (p == null || !p.getStatus().isActive()) {
            c.session().flash(false, "Patient is not in the department.");
            return Response.redirect("/queue");
        }
        Vitals v = p.getVitals();
        String body = card("Re-assess vitals: " + p.getFullName(),
                "<p>Current: " + esi(p.getTriageLevel()) + " " + e(p.getTriageLevel().getLabel()) + ", severity "
                        + String.format("%.1f", p.getSeverityScore()) + ". Enter the new measurements; triage runs again and the "
                        + "patient moves in the priority heap.</p><form method=\"post\" action=\"/reassess\"><div class=\"form\">"
                        + Html.hidden("csrf", c.csrf()) + Html.hidden("id", p.getId())
                        + num("Heart rate (bpm)", "hr", v.heartRate(), 20, 250, "1")
                        + num("Systolic BP (mmHg)", "sbp", v.systolicBp(), 40, 260, "1")
                        + num("Diastolic BP (mmHg)", "dbp", v.diastolicBp(), 20, 160, "1")
                        + num("Respiratory rate (/min)", "rr", v.respiratoryRate(), 4, 70, "1")
                        + num("SpO2 (%)", "spo2", v.spo2(), 50, 100, "1")
                        + num("Temperature (°C)", "temp", v.temperature(), 30, 44, "0.1")
                        + num("Pain score (0-10)", "pain", v.painScore(), 0, 10, "1")
                        + num("GCS (3-15)", "gcs", v.gcs(), 3, 15, "1")
                        + num("Expected resources (0-5)", "resources", p.getExpectedResources(), 0, 5, "1")
                        + "</div><p class=\"row\"><button class=\"btn btn-primary\">Save &amp; re-triage</button>"
                        + "<a class=\"btn btn-secondary\" href=\"" + (p.getStatus() == PatientStatus.WAITING ? "/queue?id=" + p.getId() : "/beds")
                        + "\">Cancel</a></p></form>");
        return Response.html(Html.page(m, c.session(), p.getStatus() == PatientStatus.WAITING ? "/queue" : "/beds",
                "Re-assess Vitals", "New measurements re-run ESI triage", body, 0));
    }

    static Response reassess(Ctx c) {
        Request r = c.req();
        int id = r.intParam("id", -1);
        Vitals v = new Vitals(r.intParam("hr", -1), r.intParam("sbp", -1), r.intParam("dbp", -1), r.intParam("rr", -1),
                r.intParam("spo2", -1), r.doubleParam("temp", Double.NaN), r.intParam("pain", -1), r.intParam("gcs", -1));
        OperationResult result = c.m().reassessPatient(id, v, r.intParam("resources", -1));
        Patient p = c.m().findPatient(id);
        String location = p != null && p.getStatus() == PatientStatus.WAITING ? "/queue?id=" + id : "/beds";
        if (!result.success() && !result.errors().isEmpty()) location = "/reassess?id=" + id;
        return c.done(result, location);
    }

    // =====================================================================
    // Beds and transfers
    // =====================================================================

    static Response beds(Ctx c) {
        HospitalManager m = c.m();
        String csrf = c.csrf();
        LocalDateTime now = m.now();
        boolean canBeds = m.hasPermission(Permission.MANAGE_BEDS);
        StringBuilder b = new StringBuilder("<div class=\"row\">")
                .append(pill("Available", "green")).append(pill("Occupied", "red")).append(pill("Cleaning", "amber"))
                .append(pill("Maintenance", "grey")).append("<span class=\"spacer\"></span>")
                .append(postButton("/admit-next", "Admit Next Patient", "btn-primary", csrf, m.hasPermission(Permission.ADMIT_PATIENT), "back", "/beds"))
                .append(postButton("/auto-allocate", "Auto-Allocate All", "btn-success", csrf, m.hasPermission(Permission.ADMIT_PATIENT), "back", "/beds"))
                .append("</div>");
        List<Bed> beds = m.allBeds();
        for (BedType type : BedType.values()) {
            StringBuilder tiles = new StringBuilder("<div class=\"tiles\">");
            int occupied = 0;
            int total = 0;
            String ward = "";
            for (Bed bed : beds) {
                if (bed.getType() != type) continue;
                total++;
                ward = bed.getWardName();
                if (bed.getStatus() == BedStatus.OCCUPIED) occupied++;
                tiles.append(tile(m, bed, csrf, canBeds, now));
            }
            tiles.append("</div>");
            if (total == 0) continue;
            b.append(card(null, "<div class=\"row\"><h2>" + e(type.getDisplayName()) + "</h2><span class=\"muted\">" + e(ward)
                    + "</span><span class=\"spacer\"></span><b>" + occupied + " / " + total + " occupied</b></div>" + tiles));
        }
        return Response.html(Html.page(m, c.session(), "/beds", "Bed Grid", "Ward capacity, admissions, transfers and discharges", b.toString(), 30));
    }

    private static String tile(HospitalManager m, Bed bed, String csrf, boolean canBeds, LocalDateTime now) {
        StringBuilder t = new StringBuilder("<div class=\"tile t-").append(bed.getStatus().name()).append("\"><div class=\"row\"><b>")
                .append(e(bed.getBedId())).append("</b><span class=\"spacer\"></span>").append(e(bed.getStatus().name())).append("</div>");
        String id = bed.getBedId();
        switch (bed.getStatus()) {
            case OCCUPIED -> {
                Patient p = bed.getPatientId() == null ? null : m.findPatient(bed.getPatientId());
                if (p != null) {
                    Doctor d = m.findDoctor(p.getAssignedDoctorId());
                    long inBed = p.getAdmittedTime() == null ? 0 : Math.max(0, Duration.between(p.getAdmittedTime(), now).toMinutes());
                    String pid = String.valueOf(p.getId());
                    t.append("<div>").append(esi(p.getTriageLevel())).append(" <a href=\"/records?id=").append(pid).append("\">")
                            .append(e(p.getFullName())).append("</a></div><span class=\"muted small\">").append(e(p.getChiefComplaint()))
                            .append("</span><span class=\"small\">").append(d == null ? "<b>No doctor assigned</b>" : e(d.getName()))
                            .append("</span><span class=\"muted small\">In bed ").append(minutes(inBed)).append("</span><div class=\"row\">");
                    if (m.hasPermission(Permission.REASSESS_PATIENT)) {
                        t.append("<a class=\"btn btn-secondary btn-small\" href=\"/reassess?id=").append(pid).append("\">Vitals</a>");
                    }
                    if (m.hasPermission(Permission.TRANSFER_PATIENT)) {
                        t.append("<a class=\"btn btn-secondary btn-small\" href=\"/transfer?id=").append(pid).append("\">Transfer</a>");
                    }
                    if (m.hasPermission(Permission.DISCHARGE_PATIENT)) {
                        t.append(postButton("/discharge", "Discharge", "btn-danger btn-small", csrf, true, "id", pid));
                    }
                    t.append("</div>");
                }
            }
            case CLEANING -> t.append("<span class=\"muted small\">Awaiting housekeeping</span>")
                    .append(postButton("/clean", "Mark Clean", "btn-success btn-small", csrf, canBeds, "bed", id));
            case AVAILABLE -> t.append("<span class=\"muted small\">Ready for admission</span>")
                    .append(postButton("/maintenance", "Take out of service", "btn-secondary btn-small", csrf, canBeds, "bed", id));
            case MAINTENANCE -> t.append("<span class=\"muted small\">Out of service</span>")
                    .append(postButton("/maintenance", "Return to service", "btn-secondary btn-small", csrf, canBeds, "bed", id));
        }
        return t.append("</div>").toString();
    }

    static Response discharge(Ctx c) { return c.done(c.m().dischargePatient(c.req().intParam("id", -1)), "/beds"); }

    static Response clean(Ctx c) { return c.done(c.m().markBedClean(c.req().param("bed")), "/beds"); }

    static Response maintenance(Ctx c) { return c.done(c.m().toggleBedMaintenance(c.req().param("bed")), "/beds"); }

    static Response transferForm(Ctx c) {
        HospitalManager m = c.m();
        Patient p = m.findPatient(c.req().intParam("id", -1));
        if (p == null || p.getStatus() != PatientStatus.ADMITTED) {
            c.session().flash(false, "Patient is not currently admitted.");
            return Response.redirect("/beds");
        }
        List<Bed> options = m.transferOptions(p.getId());
        BedType ideal = m.idealBedType(p);
        StringBuilder f = new StringBuilder("<p>").append(esi(p.getTriageLevel())).append(" <b>").append(e(p.getFullName()))
                .append("</b> is in <b>").append(e(p.getAssignedBedId())).append("</b>. Recommended ward: ")
                .append(e(ideal.getDisplayName())).append(". The old bed will go for cleaning.</p>");
        if (options.isEmpty()) {
            f.append("<p class=\"muted\">No free beds available for transfer.</p><a class=\"btn btn-secondary\" href=\"/beds\">Back</a>");
        } else {
            f.append("<form method=\"post\" action=\"/transfer\">").append(Html.hidden("csrf", c.csrf()))
                    .append(Html.hidden("id", p.getId())).append("<ul class=\"plain\">");
            boolean first = true;
            for (Bed bed : options) {
                f.append("<li><label><input type=\"radio\" name=\"bed\" value=\"").append(e(bed.getBedId())).append("\"")
                        .append(first ? " checked" : "").append("> <b>").append(e(bed.getBedId())).append("</b> ")
                        .append(e(bed.getType().getDisplayName())).append(" <span class=\"muted\">- ").append(e(bed.getWardName()))
                        .append("</span> ").append(bed.getType() == ideal ? pill("recommended", "green") : "").append("</label></li>");
                first = false;
            }
            f.append("</ul><p class=\"row\"><button class=\"btn btn-primary\">Transfer</button><a class=\"btn btn-secondary\" href=\"/beds\">Cancel</a></p></form>");
        }
        return Response.html(Html.page(m, c.session(), "/beds", "Transfer Patient", "Move an admitted patient to another bed",
                card("Choose the new bed", f.toString()), 0));
    }

    static Response transfer(Ctx c) {
        return c.done(c.m().transferPatient(c.req().intParam("id", -1), c.req().param("bed")), "/beds");
    }

    // =====================================================================
    // Doctors
    // =====================================================================

    static Response doctors(Ctx c) {
        HospitalManager m = c.m();
        boolean canManage = m.hasPermission(Permission.MANAGE_DOCTORS);
        List<Patient> admitted = m.admittedPatients();
        StringBuilder t = new StringBuilder("<p class=\"muted\">On admission every on-duty doctor with spare capacity gets a cost: "
                + "<b>specialty penalty x 10 + load ratio x 5</b> (0 = exact specialty, 1 = emergency generalist, 2 = other). "
                + "Candidates go into a min-heap (java.util.PriorityQueue) and the cheapest doctor is dispatched.</p>"
                + "<div class=\"table-wrap\"><table><tr><th>Doctor</th><th>Specialty</th><th>Workload</th><th>Status</th><th>Current patients</th><th>Duty</th></tr>");
        for (Doctor d : m.allDoctors()) {
            StringBuilder names = new StringBuilder();
            for (Patient p : admitted) {
                if (p.getAssignedDoctorId() != null && p.getAssignedDoctorId() == d.getId()) {
                    if (!names.isEmpty()) names.append(", ");
                    names.append(e(p.getFullName())).append(" (").append(e(p.getAssignedBedId())).append(")");
                }
            }
            String status = d.getStatusLabel();
            String colour = switch (status) {
                case "AVAILABLE" -> "green";
                case "BUSY" -> "blue";
                case "AT CAPACITY" -> "red";
                default -> "grey";
            };
            boolean busy = d.isOnDuty() && d.getActivePatients() > 0;
            t.append("<tr><td>").append(e(d.getName())).append("</td><td>").append(e(d.getSpecialty().getDisplayName()))
                    .append("</td><td>").append(progress(d.getActivePatients(), d.getMaxPatients())).append("<span class=\"small\">")
                    .append(d.getActivePatients()).append(" / ").append(d.getMaxPatients()).append("</span></td><td>")
                    .append(pill(status, colour)).append("</td><td>").append(names.isEmpty() ? "-" : names).append("</td><td>")
                    .append(postButton("/doctor-duty", d.isOnDuty() ? "Sign off" : "Sign on", d.isOnDuty() ? "btn-secondary btn-small" : "btn-success btn-small",
                            c.csrf(), canManage && !busy, "id", String.valueOf(d.getId())))
                    .append("</td></tr>");
        }
        t.append("</table></div>");
        return Response.html(Html.page(m, c.session(), "/doctors", "Doctor Dispatch", "Roster, workload and duty status",
                card("Roster", t.toString()), 30));
    }

    static Response doctorDuty(Ctx c) { return c.done(c.m().toggleDoctorDuty(c.req().intParam("id", -1)), "/doctors"); }

    // =====================================================================
    // Patient records
    // =====================================================================

    static Response records(Ctx c) {
        HospitalManager m = c.m();
        String q = c.req().param("q");
        if (q.length() > 100) q = q.substring(0, 100);
        String status = c.req().param("status");
        int selectedId = c.req().intParam("id", -1);

        StringBuilder search = new StringBuilder("<form method=\"get\" action=\"/records\" class=\"row\">")
                .append("<input type=\"search\" name=\"q\" placeholder=\"Search by name or MRN\" value=\"").append(e(q)).append("\">")
                .append("<select name=\"status\"><option value=\"\">All statuses</option>");
        for (PatientStatus s : PatientStatus.values()) {
            search.append("<option value=\"").append(s.name()).append("\"").append(s.name().equals(status) ? " selected" : "")
                    .append(">").append(e(statusLabel(s))).append("</option>");
        }
        search.append("</select><button class=\"btn btn-primary\">Search</button></form>");

        List<Patient> results = m.searchPatients(q, 200).stream()
                .filter(p -> status.isEmpty() || p.getStatus().name().equals(status)).toList();
        StringBuilder t = new StringBuilder("<p class=\"muted\">").append(results.size()).append(" patient(s)</p>");
        if (!results.isEmpty()) {
            t.append("<div class=\"table-wrap\"><table><tr><th>MRN</th><th>Name</th><th>Age/Sex</th><th>ESI</th><th>Status</th><th>Arrived</th><th>Bed</th></tr>");
            for (Patient p : results) {
                t.append("<tr").append(p.getId() == selectedId ? " class=\"sel\"" : "").append("><td>").append(e(p.getMrn()))
                        .append("</td><td><a href=\"/records?q=").append(e(java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)))
                        .append("&amp;status=").append(e(status)).append("&amp;id=").append(p.getId()).append("\">").append(e(p.getFullName()))
                        .append("</a></td><td>").append(p.getAge()).append(' ').append(e(p.getGender().isEmpty() ? "" : p.getGender().substring(0, 1)))
                        .append("</td><td>").append(esi(p.getTriageLevel())).append("</td><td>").append(statusPill(p.getStatus()))
                        .append("</td><td>").append(e(p.getIntakeTime().format(Html.DATE_TIME))).append("</td><td>")
                        .append(e(p.getAssignedBedId() == null ? "-" : p.getAssignedBedId())).append("</td></tr>");
            }
            t.append("</table></div>");
        }

        String detail = "<p class=\"muted\">Select a patient to see their full record: times, bed and doctor, vitals history and timeline.</p>";
        Optional<Patient> sel = selectedId > 0 ? m.findPatientRecord(selectedId) : Optional.empty();
        if (sel.isPresent()) detail = recordDetail(m, sel.get());

        String body = "<div class=\"grid g2e\">" + card("Search", search + t.toString()) + card("Patient record", detail) + "</div>";
        return Response.html(Html.page(m, c.session(), "/records", "Patient Records", "Search every patient and review their history", body, 0));
    }

    private static String recordDetail(HospitalManager m, Patient p) {
        LocalDateTime now = m.now();
        Doctor d = m.findDoctor(p.getAssignedDoctorId());
        StringBuilder b = new StringBuilder("<p><b>").append(e(p.getFullName())).append("</b> ").append(esi(p.getTriageLevel())).append(" ")
                .append(statusPill(p.getStatus())).append("<br><span class=\"muted\">").append(e(p.getMrn())).append(" | ")
                .append(p.getAge()).append(" y, ").append(e(p.getGender())).append(" | ").append(e(p.getCategory().toLowerCase()))
                .append("</span></p><p><b>").append(e(p.getChiefComplaint())).append("</b>").append(p.isTrauma() ? " (trauma)" : "")
                .append("<br>Arrived ").append(e(p.getIntakeTime().format(Html.DATE_TIME)));
        if (p.getAdmittedTime() != null) {
            b.append("<br>Admitted ").append(e(p.getAdmittedTime().format(Html.DATE_TIME))).append(" (waited ")
                    .append(minutes(p.waitingMinutes(now))).append(")");
        } else if (p.getStatus() == PatientStatus.WAITING) {
            b.append("<br>Waiting ").append(minutes(p.waitingMinutes(now)));
        }
        if (p.getDischargedTime() != null) b.append("<br>Left ").append(e(p.getDischargedTime().format(Html.DATE_TIME)));
        if (p.getAssignedBedId() != null) b.append("<br>Bed ").append(e(p.getAssignedBedId())).append(d == null ? "" : ", " + e(d.getName()));
        b.append("</p><div class=\"row\">");
        if (p.getStatus() == PatientStatus.WAITING) b.append("<a class=\"btn btn-secondary btn-small\" href=\"/queue?id=").append(p.getId()).append("\">Show in Queue Board</a>");
        if (p.getStatus() == PatientStatus.ADMITTED) b.append("<a class=\"btn btn-secondary btn-small\" href=\"/beds\">Show in Bed Grid</a>");
        if (p.getStatus().isActive() && m.hasPermission(Permission.REASSESS_PATIENT)) {
            b.append("<a class=\"btn btn-warning btn-small\" href=\"/reassess?id=").append(p.getId()).append("\">Re-assess vitals</a>");
        }
        b.append("</div><h2>Vitals history</h2>");

        List<VitalsRecord> history = m.vitalsHistory(p.getId());
        if (history.isEmpty()) {
            b.append("<p class=\"muted\">No vitals recorded.</p>");
        } else {
            b.append("<div class=\"table-wrap\"><table><tr><th>Time</th><th>HR</th><th>BP</th><th>RR</th><th>SpO2</th><th>Temp</th><th>Pain</th><th>GCS</th><th>ESI</th><th>Severity</th><th>By</th></tr>");
            for (VitalsRecord r : history) {
                Vitals v = r.vitals();
                b.append("<tr><td>").append(e(r.recordedAt().format(Html.DATE_TIME))).append("</td><td>").append(v.heartRate())
                        .append("</td><td>").append(e(v.bloodPressure())).append("</td><td>").append(v.respiratoryRate()).append("</td><td>")
                        .append(v.spo2()).append("%</td><td>").append(String.format("%.1f", v.temperature())).append("</td><td>")
                        .append(v.painScore()).append("</td><td>").append(v.gcs()).append("</td><td>").append(esi(r.level()))
                        .append("</td><td>").append(String.format("%.1f", r.severityScore())).append("</td><td>")
                        .append(e(r.recordedBy() == null ? "-" : r.recordedBy())).append("</td></tr>");
            }
            b.append("</table></div>");
        }
        b.append("<h2>Timeline</h2><ul class=\"plain\">");
        List<LogEntry> timeline = m.patientTimeline(p.getId());
        if (timeline.isEmpty()) b.append("<li class=\"muted\">No events.</li>");
        for (LogEntry l : timeline) {
            b.append("<li><span class=\"muted small\">").append(e(l.createdAt().format(Html.DATE_TIME))).append("</span> ")
                    .append(actionPill(l.action())).append(" ").append(e(l.details() == null ? "" : l.details()))
                    .append(l.performedBy() == null ? "" : " <span class=\"muted small\">by " + e(l.performedBy()) + "</span>")
                    .append("</li>");
        }
        return b.append("</ul>").toString();
    }

    static String statusLabel(PatientStatus s) {
        return switch (s) {
            case WAITING -> "Waiting";
            case ADMITTED -> "Admitted";
            case DISCHARGED -> "Discharged";
            case LEFT_WITHOUT_BEING_SEEN -> "Left without being seen";
        };
    }

    static String statusPill(PatientStatus s) {
        String colour = switch (s) {
            case WAITING -> "amber";
            case ADMITTED -> "blue";
            case DISCHARGED -> "green";
            case LEFT_WITHOUT_BEING_SEEN -> "red";
        };
        return pill(statusLabel(s), colour);
    }
}
