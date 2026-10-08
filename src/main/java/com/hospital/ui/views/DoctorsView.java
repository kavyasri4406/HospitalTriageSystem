package com.hospital.ui.views;

import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.service.HospitalManager;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Doctor roster with workload and duty toggle. */
public class DoctorsView implements View {

    private final UiContext ctx;
    private final HospitalManager manager;
    private final TableView<Doctor> table = new TableView<>();
    private final VBox root;

    public DoctorsView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();
        buildTable();

        VBox explanation = Ui.card("How doctors are dispatched",
                Ui.label("When a patient is admitted, every on-duty doctor with spare capacity is scored:"),
                Ui.label("cost = specialty penalty x 10  +  load ratio x 5", "bold"),
                Ui.label("Specialty penalty: 0 = exact match (e.g. trauma -> Trauma Surgery), "
                        + "1 = Emergency Medicine generalist, 2 = other specialty. "
                        + "Candidates go into a min-heap (java.util.PriorityQueue) and the cheapest doctor is chosen."));
        explanation.getChildren().forEach(n -> { if (n instanceof javafx.scene.control.Label l) l.setWrapText(true); });

        root = new VBox(16, explanation, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setPadding(new Insets(20, 22, 22, 22));
    }

    private void buildTable() {
        TableColumn<Doctor, Doctor> load = new TableColumn<>("Workload");
        load.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        load.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Doctor d, boolean empty) {
                super.updateItem(d, empty);
                setText(null);
                if (empty || d == null) { setGraphic(null); return; }
                ProgressBar bar = Ui.ratioBar(d.loadRatio());
                bar.setPrefWidth(120);
                setGraphic(Ui.row(8, bar, Ui.label(d.getActivePatients() + " / " + d.getMaxPatients())));
            }
        });
        load.setPrefWidth(200);

        TableColumn<Doctor, Doctor> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Doctor d, boolean empty) {
                super.updateItem(d, empty);
                setText(null);
                if (empty || d == null) { setGraphic(null); return; }
                String label = d.getStatusLabel();
                String color = switch (label) {
                    case "AVAILABLE" -> "green";
                    case "BUSY" -> "blue";
                    case "AT CAPACITY" -> "red";
                    default -> "grey";
                };
                setGraphic(Ui.pill(label, color));
            }
        });
        status.setPrefWidth(110);

        TableColumn<Doctor, Doctor> action = new TableColumn<>("Duty");
        action.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        action.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Doctor d, boolean empty) {
                super.updateItem(d, empty);
                setText(null);
                if (empty || d == null) { setGraphic(null); return; }
                Button b = Ui.button(d.isOnDuty() ? "Sign off" : "Sign on", d.isOnDuty() ? "btn-secondary" : "btn-success",
                        () -> ctx.showResult(manager.toggleDoctorDuty(d.getId())));
                b.getStyleClass().add("btn-small");
                b.setDisable((d.isOnDuty() && d.getActivePatients() > 0)
                        || !manager.hasPermission(com.hospital.model.Permission.MANAGE_DOCTORS));
                if (d.isOnDuty() && d.getActivePatients() > 0) {
                    b.setTooltip(new javafx.scene.control.Tooltip("Cannot sign off while responsible for patients"));
                }
                setGraphic(b);
            }
        });
        action.setPrefWidth(100);

        table.getColumns().add(text("Doctor", 170, Doctor::getName));
        table.getColumns().add(text("Specialty", 160, d -> d.getSpecialty().getDisplayName()));
        table.getColumns().add(load);
        table.getColumns().add(status);
        table.getColumns().add(text("Current patients", 330, this::patientsOf));
        table.getColumns().add(action);
        table.setPlaceholder(Ui.label("No doctors on the roster.", "muted"));
        HBox.setHgrow(table, Priority.ALWAYS);
    }

    private String patientsOf(Doctor d) {
        List<String> names = new ArrayList<>();
        for (Patient p : manager.admittedPatients()) {
            if (p.getAssignedDoctorId() != null && p.getAssignedDoctorId() == d.getId()) {
                names.add(p.getFullName() + " (" + p.getAssignedBedId() + ")");
            }
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private static TableColumn<Doctor, String> text(String title, double width, Function<Doctor, String> getter) {
        TableColumn<Doctor, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(getter.apply(c.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        table.setItems(FXCollections.observableArrayList(manager.allDoctors()));
        table.refresh();
    }
}
