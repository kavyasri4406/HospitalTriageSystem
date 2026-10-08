package com.hospital.ui.views;

import com.hospital.model.Alert;
import com.hospital.model.BedType;
import com.hospital.model.Patient;
import com.hospital.model.Permission;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.service.AnalyticsSnapshot;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.ui.Page;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;

/** Landing screen: KPIs, quick actions, next patients, occupancy and recent alerts. */
public class DashboardView implements View {

    private final UiContext ctx;
    private final HospitalManager manager;
    private final ScrollPane root;

    private final Label waitingValue = Ui.label("0", "kpi-value");
    private final Label criticalValue = Ui.label("0", "kpi-value");
    private final Label overdueValue = Ui.label("0", "kpi-value");
    private final Label bedsValue = Ui.label("0", "kpi-value");
    private final Label doctorsValue = Ui.label("0", "kpi-value");
    private final Label avgWaitValue = Ui.label("0", "kpi-value");

    private final VBox nextPatients = new VBox(6);
    private final VBox occupancy = new VBox(10);
    private final VBox recentAlerts = new VBox(6);
    private final VBox activity = new VBox(4);

    public DashboardView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();

        HBox kpis = new HBox(14,
                kpi("Patients waiting", waitingValue, "#2563eb"),
                kpi("Critical (ESI 1-2) waiting", criticalValue, "#d32f2f"),
                kpi("Over target wait", overdueValue, "#f57c00"),
                kpi("Beds available", bedsValue, "#16a34a"),
                kpi("Doctors available", doctorsValue, "#7c3aed"),
                kpi("Average wait", avgWaitValue, "#0891b2"));

        HBox actions = Ui.row(10,
                allowed(Ui.button("Admit Next Patient", "btn-primary", () -> ctx.showResult(manager.admitNext())),
                        Permission.ADMIT_PATIENT),
                allowed(Ui.button("Auto-Allocate All Beds", "btn-success", () -> ctx.showResult(manager.autoAllocate())),
                        Permission.ADMIT_PATIENT),
                allowed(Ui.button("+ Register Patient", "btn-secondary", () -> ctx.navigate(Page.INTAKE)),
                        Permission.REGISTER_PATIENT),
                allowed(Ui.button("Simulate 5 Arrivals", "btn-secondary", () -> {
                    List<OperationResult> results = manager.simulateArrivals(5);
                    boolean ok = !results.isEmpty() && results.get(0).success();
                    ctx.showResult(ok ? OperationResult.ok(results.size() + " simulated patients arrived and were triaged.")
                            : results.get(0));
                }), Permission.SIMULATE),
                allowed(Ui.button("Advance Clock +15 min", "btn-warning", () -> ctx.showResult(manager.advanceClock(15))),
                        Permission.SIMULATE),
                Ui.hgrow(),
                allowed(Ui.button("Reset Demo Data", "btn-danger", () -> {
                    if (Ui.confirm(ctx.window(), "Reset demo data",
                            "Delete all patients, alerts and logs and free every bed?")) {
                        ctx.showResult(manager.resetDemoData());
                    }
                }), Permission.RESET_DATA));

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(58);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(42);
        grid.getColumnConstraints().addAll(left, right);

        VBox nextCard = Ui.card("Next in queue (max-heap order)", nextPatients);
        VBox occCard = Ui.card("Bed occupancy by ward", occupancy);
        VBox alertCard = Ui.card("Latest alerts", recentAlerts);
        VBox activityCard = Ui.card("Recent activity (admission log)", activity);
        grid.add(nextCard, 0, 0);
        grid.add(occCard, 1, 0);
        grid.add(activityCard, 0, 1);
        grid.add(alertCard, 1, 1);

        VBox content = new VBox(18, kpis, actions, grid);
        content.setPadding(new Insets(20, 22, 22, 22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
    }

    /** Disables an action the logged-in user's role may not perform (the backend enforces it too). */
    private Button allowed(Button button, Permission permission) {
        if (!manager.hasPermission(permission)) {
            button.setDisable(true);
            button.setTooltip(new Tooltip("Not permitted for your role: " + permission.getDescription()));
        }
        return button;
    }

    private static VBox kpi(String label, Label value, String accent) {
        Region stripe = new Region();
        stripe.setPrefSize(34, 4);
        stripe.setMaxWidth(34);
        stripe.setStyle("-fx-background-color: " + accent + "; -fx-background-radius: 2;");
        VBox card = new VBox(6, stripe, value, Ui.label(label, "kpi-label"));
        card.getStyleClass().add("card");
        card.setMinWidth(150);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        AnalyticsSnapshot s = manager.analytics();
        waitingValue.setText(String.valueOf(s.waitingCount()));
        criticalValue.setText(String.valueOf(s.criticalWaitingCount()));
        overdueValue.setText(String.valueOf(s.overdueCount()));
        bedsValue.setText(s.bedsAvailable() + " / " + s.bedsTotal());
        doctorsValue.setText(s.doctorsAvailable() + " / " + s.doctorsOnDuty());
        avgWaitValue.setText(Ui.minutes(Math.round(s.averageWaitMinutes())));

        refreshNextPatients();
        refreshOccupancy(s);
        refreshAlerts();
        refreshActivity();
    }

    private void refreshNextPatients() {
        nextPatients.getChildren().clear();
        List<Patient> queue = manager.waitingQueue();
        if (queue.isEmpty()) {
            nextPatients.getChildren().add(Ui.label("No patients waiting. Use \"Register Patient\" or \"Simulate 5 Arrivals\".", "muted"));
            return;
        }
        LocalDateTime now = manager.now();
        int rank = 1;
        for (Patient p : queue.subList(0, Math.min(8, queue.size()))) {
            Label name = Ui.label(p.getFullName(), "bold");
            Label complaint = Ui.label(p.getChiefComplaint(), "muted", "small");
            VBox who = new VBox(1, name, complaint);
            HBox.setHgrow(who, Priority.ALWAYS);
            Label wait = p.isOverdue(now) ? Ui.pill(Ui.minutes(p.waitingMinutes(now)) + " overdue", "red")
                    : Ui.pill(Ui.minutes(p.waitingMinutes(now)), "grey");
            Label priority = Ui.label(String.format("%.1f", p.getPriorityScore()), "bold");
            priority.setMinWidth(40);
            priority.setAlignment(Pos.CENTER_RIGHT);
            HBox row = Ui.row(10, Ui.label("#" + rank++, "muted"), Ui.esiBadge(p.getTriageLevel()), who, wait, priority);
            nextPatients.getChildren().add(row);
        }
        if (queue.size() > 8) {
            nextPatients.getChildren().add(Ui.button("View all " + queue.size() + " on the Queue Board", "btn-secondary",
                    () -> ctx.navigate(Page.QUEUE)));
        }
    }

    private void refreshOccupancy(AnalyticsSnapshot s) {
        occupancy.getChildren().clear();
        for (BedType type : BedType.values()) {
            int[] counts = s.occupancyByType().get(type);
            double ratio = counts[1] == 0 ? 0 : (double) counts[0] / counts[1];
            HBox header = Ui.row(6, Ui.label(type.getDisplayName(), "bold"), Ui.hgrow(),
                    Ui.label(counts[0] + " / " + counts[1] + " occupied", "muted", "small"));
            occupancy.getChildren().add(new VBox(4, header, Ui.ratioBar(ratio)));
        }
        occupancy.getChildren().add(Ui.label(String.format("Overall occupancy %.0f%%  |  %d cleaning  |  %d maintenance",
                s.occupancyRate() * 100,
                s.bedsByStatus().get(com.hospital.model.BedStatus.CLEANING),
                s.bedsByStatus().get(com.hospital.model.BedStatus.MAINTENANCE)), "muted", "small"));
    }

    private void refreshAlerts() {
        recentAlerts.getChildren().clear();
        List<Alert> alerts = manager.recentAlerts();
        if (alerts.isEmpty()) {
            recentAlerts.getChildren().add(Ui.label("No alerts.", "muted"));
            return;
        }
        for (Alert a : alerts.subList(0, Math.min(6, alerts.size()))) {
            Region dot = new Region();
            dot.setMinSize(9, 9);
            dot.setMaxSize(9, 9);
            dot.setStyle("-fx-background-color: " + a.getSeverity().getColorHex() + "; -fx-background-radius: 9;");
            Label msg = Ui.label(a.getMessage(), a.isAcknowledged() ? "muted" : "bold");
            msg.setWrapText(true);
            msg.setMaxWidth(Double.MAX_VALUE);
            msg.setMinHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(msg, Priority.ALWAYS);
            Label time = Ui.label(a.getCreatedAt().format(Ui.TIME), "muted", "small");
            time.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = Ui.row(8, dot, time, msg);
            row.setAlignment(Pos.TOP_LEFT);
            recentAlerts.getChildren().add(row);
        }
        recentAlerts.getChildren().add(Ui.button("Open alerts", "btn-secondary", () -> ctx.navigate(Page.ALERTS)));
    }

    private void refreshActivity() {
        activity.getChildren().clear();
        List<LogEntry> entries = manager.recentActivity(8);
        if (entries.isEmpty()) {
            activity.getChildren().add(Ui.label("No activity yet.", "muted"));
            return;
        }
        for (LogEntry e : entries) {
            Patient p = manager.findPatient(e.patientId());
            String who = p != null ? p.getFullName() : "Patient #" + e.patientId();
            String color = switch (e.action()) {
                case "ADMITTED" -> "green";
                case "DISCHARGED", "TRANSFERRED" -> "blue";
                case "REASSESSED" -> "amber";
                case "LEFT_WITHOUT_BEING_SEEN" -> "red";
                default -> "grey";
            };
            Label detail = Ui.label(who + (e.bedId() != null ? "  ->  " + e.bedId() : ""), "small");
            Label by = Ui.label(e.performedBy() == null ? "" : "by " + e.performedBy(), "muted", "small");
            HBox row = Ui.row(8, Ui.label(e.createdAt().format(Ui.TIME), "muted", "small"),
                    Ui.pill(e.action().replace('_', ' '), color), detail, Ui.hgrow(), by);
            activity.getChildren().add(row);
        }
    }
}
