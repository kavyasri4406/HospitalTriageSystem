package com.hospital.ui.views;

import com.hospital.model.BedType;
import com.hospital.model.PatientStatus;
import com.hospital.model.TriageLevel;
import com.hospital.service.AnalyticsSnapshot;
import com.hospital.service.AnalyticsSnapshot.DoctorLoad;
import com.hospital.service.HospitalManager;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** Charts of the current state of the department. */
public class AnalyticsView implements View {

    private final HospitalManager manager;
    private final ScrollPane root;

    private final PieChart levelPie = new PieChart();
    private final BarChart<String, Number> waitChart = new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final StackedBarChart<String, Number> bedChart = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
    private final BarChart<String, Number> doctorChart = new BarChart<>(new CategoryAxis(), new NumberAxis());

    private final Label registered = Ui.label("0", "kpi-value");
    private final Label admitted = Ui.label("0", "kpi-value");
    private final Label discharged = Ui.label("0", "kpi-value");
    private final Label lwbs = Ui.label("0", "kpi-value");
    private final Label doorToBed = Ui.label("0", "kpi-value");
    private final Label occupancy = Ui.label("0", "kpi-value");

    public AnalyticsView(UiContext ctx) {
        this.manager = ctx.manager();

        levelPie.setTitle("Waiting patients by ESI level");
        levelPie.setLabelsVisible(true);
        levelPie.setLegendVisible(false);
        levelPie.setAnimated(false);

        waitChart.setTitle("Average wait vs target (minutes)");
        waitChart.setAnimated(false);
        waitChart.getYAxis().setLabel("minutes");

        bedChart.setTitle("Bed occupancy by ward");
        bedChart.setAnimated(false);
        bedChart.getYAxis().setLabel("beds");

        doctorChart.setTitle("Doctor workload (active patients / capacity)");
        doctorChart.setAnimated(false);
        doctorChart.getYAxis().setLabel("patients");

        HBox stats = new HBox(14,
                stat("Total registered", registered), stat("Currently admitted", admitted),
                stat("Discharged", discharged), stat("Left without being seen", lwbs),
                stat("Avg door-to-bed (admitted)", doorToBed), stat("Bed occupancy", occupancy));

        GridPane charts = new GridPane();
        charts.setHgap(16);
        charts.setVgap(16);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        charts.getColumnConstraints().addAll(half, half);
        charts.add(chartCard(levelPie), 0, 0);
        charts.add(chartCard(waitChart), 1, 0);
        charts.add(chartCard(bedChart), 0, 1);
        charts.add(chartCard(doctorChart), 1, 1);

        VBox content = new VBox(18, stats, charts);
        content.setPadding(new Insets(20, 22, 22, 22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
    }

    private static VBox stat(String label, Label value) {
        VBox card = new VBox(4, value, Ui.label(label, "kpi-label"));
        card.getStyleClass().add("card");
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static VBox chartCard(Node chart) {
        VBox card = Ui.card(null, chart);
        card.setPrefHeight(340);
        VBox.setVgrow(chart, Priority.ALWAYS);
        return card;
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        AnalyticsSnapshot s = manager.analytics();
        Map<PatientStatus, Integer> byStatus = s.patientsByStatus();
        int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();
        registered.setText(String.valueOf(total));
        admitted.setText(String.valueOf(byStatus.get(PatientStatus.ADMITTED)));
        discharged.setText(String.valueOf(byStatus.get(PatientStatus.DISCHARGED)));
        lwbs.setText(String.valueOf(byStatus.get(PatientStatus.LEFT_WITHOUT_BEING_SEEN)));
        doorToBed.setText(Ui.minutes(Math.round(s.averageDoorToBedMinutes())));
        occupancy.setText(String.format("%.0f%%", s.occupancyRate() * 100));

        // Pie: waiting patients per ESI level
        levelPie.getData().clear();
        for (TriageLevel level : TriageLevel.values()) {
            int n = s.waitingByLevel().get(level);
            if (n == 0) continue;
            PieChart.Data slice = new PieChart.Data("ESI " + level.getEsi() + " (" + n + ")", n);
            levelPie.getData().add(slice);
            String color = level.getColorHex();
            slice.nodeProperty().addListener((o, a, node) -> { if (node != null) node.setStyle("-fx-pie-color: " + color + ";"); });
            if (slice.getNode() != null) slice.getNode().setStyle("-fx-pie-color: " + color + ";");
        }
        if (levelPie.getData().isEmpty()) {
            levelPie.getData().add(new PieChart.Data("No one waiting", 1));
        }

        // Wait vs target
        XYChart.Series<String, Number> avg = new XYChart.Series<>();
        avg.setName("Average wait");
        XYChart.Series<String, Number> target = new XYChart.Series<>();
        target.setName("Target");
        for (TriageLevel level : TriageLevel.values()) {
            String key = "ESI " + level.getEsi();
            avg.getData().add(new XYChart.Data<>(key, Math.round(s.averageWaitByLevel().get(level) * 10) / 10.0));
            target.getData().add(new XYChart.Data<>(key, level.getTargetWaitMinutes()));
        }
        waitChart.getData().setAll(List.of(avg, target));

        // Beds occupied vs free
        XYChart.Series<String, Number> occ = new XYChart.Series<>();
        occ.setName("Occupied");
        XYChart.Series<String, Number> free = new XYChart.Series<>();
        free.setName("Not occupied");
        for (BedType type : BedType.values()) {
            int[] c = s.occupancyByType().get(type);
            occ.getData().add(new XYChart.Data<>(type.getPrefix(), c[0]));
            free.getData().add(new XYChart.Data<>(type.getPrefix(), c[1] - c[0]));
        }
        bedChart.getData().setAll(List.of(occ, free));

        // Doctor workload
        XYChart.Series<String, Number> active = new XYChart.Series<>();
        active.setName("Active patients");
        XYChart.Series<String, Number> capacity = new XYChart.Series<>();
        capacity.setName("Capacity");
        for (DoctorLoad d : s.doctorLoads()) {
            String key = d.name().replace("Dr. ", "") + (d.onDuty() ? "" : " (off)");
            active.getData().add(new XYChart.Data<>(key, d.active()));
            capacity.getData().add(new XYChart.Data<>(key, d.max()));
        }
        doctorChart.getData().setAll(List.of(active, capacity));
    }
}
