package com.hospital.ui.views;

import com.hospital.model.Alert;
import com.hospital.model.AlertSeverity;
import com.hospital.service.HospitalManager;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** All alerts, filterable, with acknowledgement. */
public class AlertsView implements View {

    private static final String ALL = "All alerts";
    private static final String UNACKED = "Unacknowledged";

    private final HospitalManager manager;
    private final ListView<Alert> list = new ListView<>();
    private final ComboBox<String> filter = new ComboBox<>();
    private final Label count = Ui.label("", "muted");
    private final VBox root;

    public AlertsView(UiContext ctx) {
        this.manager = ctx.manager();

        filter.getItems().addAll(ALL, UNACKED, "CRITICAL", "WARNING", "INFO");
        filter.setValue(ALL);
        filter.valueProperty().addListener((o, a, b) -> refresh());

        list.setCellFactory(lv -> new AlertCell());
        list.setPlaceholder(Ui.label("No alerts match this filter.", "muted"));

        HBox toolbar = Ui.row(10, Ui.label("Show:", "form-label"), filter, count, Ui.hgrow(),
                Ui.button("Acknowledge All", "btn-primary", manager::acknowledgeAllAlerts));

        root = new VBox(14, toolbar, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        root.setPadding(new Insets(20, 22, 22, 22));
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        String f = filter.getValue();
        List<Alert> filtered = manager.recentAlerts().stream()
                .filter(a -> switch (f) {
                    case ALL -> true;
                    case UNACKED -> !a.isAcknowledged();
                    default -> a.getSeverity() == AlertSeverity.valueOf(f);
                })
                .toList();
        list.setItems(FXCollections.observableArrayList(filtered));
        count.setText(filtered.size() + " shown, " + manager.unacknowledgedAlertCount() + " unacknowledged");
    }

    private class AlertCell extends ListCell<Alert> {
        @Override
        protected void updateItem(Alert a, boolean empty) {
            super.updateItem(a, empty);
            setText(null);
            if (empty || a == null) {
                setGraphic(null);
                return;
            }
            Region stripe = new Region();
            stripe.setMinWidth(5);
            stripe.setMaxWidth(5);
            stripe.setStyle("-fx-background-color: " + a.getSeverity().getColorHex() + "; -fx-background-radius: 3;");

            String pillColor = switch (a.getSeverity()) {
                case CRITICAL -> "red";
                case WARNING -> "amber";
                case INFO -> "blue";
            };
            Label message = Ui.label(a.getMessage(), "bold");
            message.setWrapText(true);
            VBox text = new VBox(3,
                    Ui.row(8, Ui.pill(a.getSeverity().name(), pillColor),
                            Ui.label(a.getCategory().name().replace('_', ' '), "muted", "small"),
                            Ui.label(a.getCreatedAt().format(Ui.DATE_TIME), "muted", "small")),
                    message);
            HBox.setHgrow(text, Priority.ALWAYS);

            HBox row = new HBox(10, stripe, text);
            row.getStyleClass().add("alert-row");
            row.setAlignment(Pos.CENTER_LEFT);
            if (a.isAcknowledged()) {
                row.getStyleClass().add("alert-row-acked");
                row.getChildren().add(Ui.label("Acknowledged", "muted", "small"));
            } else {
                Button ack = Ui.button("Acknowledge", "btn-secondary", () -> manager.acknowledgeAlert(a.getId()));
                ack.getStyleClass().add("btn-small");
                row.getChildren().add(ack);
            }
            row.setMaxWidth(Double.MAX_VALUE);
            row.prefWidthProperty().bind(list.widthProperty().subtract(30));
            setGraphic(row);
        }
    }
}
