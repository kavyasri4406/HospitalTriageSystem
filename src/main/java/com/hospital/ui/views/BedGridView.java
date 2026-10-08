package com.hospital.ui.views;

import com.hospital.model.Bed;
import com.hospital.model.BedStatus;
import com.hospital.model.BedType;
import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.service.HospitalManager;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Visual grid of every bed, grouped by ward type, with admit/discharge/cleaning actions. */
public class BedGridView implements View {

    private final UiContext ctx;
    private final HospitalManager manager;
    private final VBox sections = new VBox(18);
    private final ScrollPane root;

    public BedGridView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();

        HBox legend = Ui.row(16,
                legendItem("Available", "#22c55e"), legendItem("Occupied", "#ef4444"),
                legendItem("Cleaning", "#f59e0b"), legendItem("Maintenance", "#94a3b8"),
                Ui.hgrow(),
                Ui.button("Admit Next Patient", "btn-primary", () -> ctx.showResult(manager.admitNext())),
                Ui.button("Auto-Allocate All", "btn-success", () -> ctx.showResult(manager.autoAllocate())));

        VBox content = new VBox(16, legend, sections);
        content.setPadding(new Insets(20, 22, 22, 22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
    }

    private static HBox legendItem(String text, String color) {
        Region swatch = new Region();
        swatch.setMinSize(14, 14);
        swatch.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 4;");
        return Ui.row(6, swatch, Ui.label(text));
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        sections.getChildren().clear();
        List<Bed> beds = manager.allBeds();
        for (BedType type : BedType.values()) {
            FlowPane tiles = new FlowPane(12, 12);
            int occupied = 0;
            int total = 0;
            String ward = "";
            for (Bed bed : beds) {
                if (bed.getType() != type) continue;
                total++;
                if (bed.getStatus() == BedStatus.OCCUPIED) occupied++;
                ward = bed.getWardName();
                tiles.getChildren().add(tile(bed));
            }
            if (total == 0) continue;
            HBox header = Ui.row(10, Ui.label(type.getDisplayName(), "section-title"), Ui.label(ward, "muted"),
                    Ui.hgrow(), Ui.label(occupied + " / " + total + " occupied", "bold"));
            sections.getChildren().add(Ui.card(null, header, tiles));
        }
    }

    private VBox tile(Bed bed) {
        VBox tile = new VBox();
        tile.getStyleClass().addAll("bed-tile", "bed-" + bed.getStatus().name().toLowerCase(Locale.ROOT));
        Label status = switch (bed.getStatus()) {
            case AVAILABLE -> Ui.pill("AVAILABLE", "green");
            case OCCUPIED -> Ui.pill("OCCUPIED", "red");
            case CLEANING -> Ui.pill("CLEANING", "amber");
            case MAINTENANCE -> Ui.pill("MAINTENANCE", "grey");
        };
        tile.getChildren().add(Ui.row(6, Ui.label(bed.getBedId(), "bed-id"), Ui.hgrow(), status));

        switch (bed.getStatus()) {
            case OCCUPIED -> {
                Patient p = bed.getPatientId() == null ? null : manager.findPatient(bed.getPatientId());
                if (p != null) {
                    Label name = Ui.label(p.getFullName(), "bold");
                    name.setWrapText(true);
                    tile.getChildren().add(Ui.row(6, Ui.esiBadge(p.getTriageLevel()), name));
                    Label complaint = Ui.label(p.getChiefComplaint(), "muted", "small");
                    complaint.setWrapText(true);
                    tile.getChildren().add(complaint);
                    Doctor d = manager.findDoctor(p.getAssignedDoctorId());
                    tile.getChildren().add(Ui.label(d == null ? "No doctor assigned" : d.getName(),
                            d == null ? "error-text" : "small"));
                    if (p.getAdmittedTime() != null) {
                        long mins = Math.max(0, Duration.between(p.getAdmittedTime(), manager.now()).toMinutes());
                        tile.getChildren().add(Ui.label("In bed " + Ui.minutes(mins), "muted", "small"));
                    }
                    tile.getChildren().add(small(Ui.button("Discharge", "btn-danger", () -> discharge(p, bed))));
                }
            }
            case CLEANING -> {
                tile.getChildren().add(Ui.label("Awaiting housekeeping", "muted", "small"));
                tile.getChildren().add(small(Ui.button("Mark Clean", "btn-success",
                        () -> ctx.showResult(manager.markBedClean(bed.getBedId())))));
            }
            case AVAILABLE -> {
                tile.getChildren().add(Ui.label("Ready for admission", "muted", "small"));
                tile.getChildren().add(small(Ui.button("Take out of service", "btn-secondary",
                        () -> ctx.showResult(manager.toggleBedMaintenance(bed.getBedId())))));
            }
            case MAINTENANCE -> {
                tile.getChildren().add(Ui.label("Out of service", "muted", "small"));
                tile.getChildren().add(small(Ui.button("Return to service", "btn-secondary",
                        () -> ctx.showResult(manager.toggleBedMaintenance(bed.getBedId())))));
            }
        }
        return tile;
    }

    private static Node small(javafx.scene.control.Button b) {
        b.getStyleClass().add("btn-small");
        return b;
    }

    private void discharge(Patient p, Bed bed) {
        if (Ui.confirm(ctx.window(), "Discharge patient",
                "Discharge " + p.getFullName() + " from " + bed.getBedId() + "? The bed will be sent for cleaning.")) {
            ctx.showResult(manager.dischargePatient(p.getId()));
        }
    }
}
