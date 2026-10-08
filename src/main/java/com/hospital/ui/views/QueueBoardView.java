package com.hospital.ui.views;

import com.hospital.model.Patient;
import com.hospital.model.Vitals;
import com.hospital.service.HospitalManager;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/** The waiting room, ordered by the custom max-heap priority queue. */
public class QueueBoardView implements View {

    private final UiContext ctx;
    private final HospitalManager manager;
    private final BorderPane root = new BorderPane();
    private final TableView<Patient> table = new TableView<>();
    private final VBox details = new VBox(8);
    private final Label summary = Ui.label("", "muted");
    private LocalDateTime now = LocalDateTime.now();

    public QueueBoardView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();
        buildTable();

        HBox toolbar = Ui.row(10,
                Ui.button("Admit Next", "btn-primary", () -> ctx.showResult(manager.admitNext())),
                Ui.button("Admit Selected", "btn-success", this::admitSelected),
                Ui.button("Auto-Allocate", "btn-secondary", () -> ctx.showResult(manager.autoAllocate())),
                Ui.button("Left Without Being Seen", "btn-danger", this::removeSelected));
        toolbar.getChildren().forEach(n -> ((Region) n).setMinWidth(Region.USE_PREF_SIZE));
        summary.setMinWidth(Region.USE_PREF_SIZE);
        VBox top = new VBox(8, toolbar, summary);

        VBox detailCard = Ui.card("Patient details", details);
        detailCard.setPrefWidth(340);
        ScrollPane detailScroll = new ScrollPane(detailCard);
        detailScroll.setFitToWidth(true);
        detailScroll.setPrefWidth(360);

        VBox center = new VBox(12, top, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setCenter(center);
        root.setRight(detailScroll);
        BorderPane.setMargin(detailScroll, new Insets(0, 0, 0, 16));
        root.setPadding(new Insets(20, 22, 22, 22));

        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetails(b));
        showDetails(null);
    }

    private void buildTable() {
        TableColumn<Patient, Number> rank = new TableColumn<>("#");
        rank.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(table.getItems().indexOf(c.getValue()) + 1));
        rank.setPrefWidth(40);

        TableColumn<Patient, Patient> esi = new TableColumn<>("ESI");
        esi.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        esi.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Patient p, boolean empty) {
                super.updateItem(p, empty);
                setText(null);
                setGraphic(empty || p == null ? null : Ui.esiBadge(p.getTriageLevel()));
            }
        });
        esi.setPrefWidth(70);

        TableColumn<Patient, Patient> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Patient p, boolean empty) {
                super.updateItem(p, empty);
                setText(null);
                if (empty || p == null) { setGraphic(null); return; }
                setGraphic(p.isOverdue(now) ? Ui.pill("OVERDUE", "red") : Ui.pill("On time", "green"));
            }
        });
        status.setPrefWidth(90);

        table.getColumns().add(rank);
        table.getColumns().add(esi);
        table.getColumns().add(text("Name", 140, Patient::getFullName));
        table.getColumns().add(text("Age/Sex", 70, p -> p.getAge() + " " + p.getGender().charAt(0)));
        table.getColumns().add(text("Complaint", 200, Patient::getChiefComplaint));
        table.getColumns().add(text("Severity", 65, p -> String.format("%.1f", p.getSeverityScore())));
        table.getColumns().add(text("Aging +", 60, p -> String.format("%.1f", p.getAgingBonus())));
        table.getColumns().add(text("Priority", 65, p -> String.format("%.1f", p.getPriorityScore())));
        table.getColumns().add(text("Waiting", 70, p -> Ui.minutes(p.waitingMinutes(now))));
        table.getColumns().add(text("Target", 60, p -> p.getTriageLevel().getTargetWaitMinutes() + " min"));
        table.getColumns().add(status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Patient p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().remove("overdue");
                if (!empty && p != null && p.isOverdue(now)) getStyleClass().add("overdue");
            }
        });
        table.setPlaceholder(Ui.label("The waiting room is empty.", "muted"));
        table.getColumns().forEach(c -> c.setSortable(false)); // order comes from the heap
    }

    private static TableColumn<Patient, String> text(String title, double width, Function<Patient, String> getter) {
        TableColumn<Patient, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(getter.apply(c.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        now = manager.now();
        Patient selected = table.getSelectionModel().getSelectedItem();
        List<Patient> queue = manager.waitingQueue();
        table.setItems(FXCollections.observableArrayList(queue));
        if (selected != null && queue.contains(selected)) {
            table.getSelectionModel().select(selected);
        }
        table.refresh();
        long critical = queue.stream().filter(p -> p.getTriageLevel().isCritical()).count();
        long overdue = queue.stream().filter(p -> p.isOverdue(now)).count();
        summary.setText(queue.size() + " waiting  |  " + critical + " critical  |  " + overdue + " overdue");
        showDetails(table.getSelectionModel().getSelectedItem());
    }

    private void admitSelected() {
        Patient p = table.getSelectionModel().getSelectedItem();
        if (p == null) {
            ctx.showResult(com.hospital.service.OperationResult.fail("Select a patient in the table first."));
            return;
        }
        ctx.showResult(manager.admitPatient(p.getId()));
    }

    private void removeSelected() {
        Patient p = table.getSelectionModel().getSelectedItem();
        if (p == null) {
            ctx.showResult(com.hospital.service.OperationResult.fail("Select a patient in the table first."));
            return;
        }
        if (Ui.confirm(ctx.window(), "Left without being seen",
                "Remove " + p.getFullName() + " from the queue as 'left without being seen'?")) {
            ctx.showResult(manager.markLeftWithoutBeingSeen(p.getId()));
        }
    }

    private void showDetails(Patient p) {
        details.getChildren().clear();
        if (p == null) {
            details.getChildren().add(Ui.label("Select a patient to see vitals and the triage reasoning.", "muted"));
            return;
        }
        Vitals v = p.getVitals();
        Label badge = Ui.label("ESI " + p.getTriageLevel().getEsi() + "  " + p.getTriageLevel().getLabel(), "esi-badge");
        badge.setStyle(Ui.esiStyle(p.getTriageLevel()));
        details.getChildren().addAll(
                Ui.label(p.getFullName(), "section-title"),
                Ui.label(p.getMrn() + "  |  " + p.getAge() + " y, " + p.getGender() + "  |  " + p.getCategory().toLowerCase(), "muted"),
                badge,
                Ui.label(p.getChiefComplaint() + (p.isTrauma() ? "  (trauma)" : ""), "bold"),
                Ui.label("Arrived " + p.getIntakeTime().format(Ui.DATE_TIME) + "  -  waiting " + Ui.minutes(p.waitingMinutes(now)), "muted"),
                Ui.label("Vitals", "form-label"),
                Ui.label("HR " + v.heartRate() + " bpm    BP " + v.bloodPressure() + " mmHg"),
                Ui.label("RR " + v.respiratoryRate() + " /min    SpO2 " + v.spo2() + "%"),
                Ui.label(String.format("Temp %.1f °C    Pain %d/10    GCS %d", v.temperature(), v.painScore(), v.gcs())),
                Ui.label("Expected resources: " + p.getExpectedResources()),
                Ui.label("Scoring", "form-label"),
                Ui.label(String.format("Severity %.1f  +  aging %.1f  =  priority %.1f",
                        p.getSeverityScore(), p.getAgingBonus(), p.getPriorityScore())),
                Ui.label("Needs bed: " + manager.idealBedType(p).getDisplayName()),
                Ui.label("ESI reasoning", "form-label"));
        for (String reason : manager.triageReasons(p.getId())) {
            Label l = Ui.label("•  " + reason);
            l.setWrapText(true);
            details.getChildren().add(l);
        }
    }
}
