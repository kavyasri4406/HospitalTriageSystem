package com.hospital.ui.views;

import com.hospital.model.AppSettings;
import com.hospital.model.Permission;
import com.hospital.model.StaffUser;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** System settings (admin only) plus keyboard shortcuts and system information. */
public class SettingsView implements View {

    private final UiContext ctx;
    private final HospitalManager manager;
    private final ScrollPane root;

    private final CheckBox autoAdmit = new CheckBox("Auto-admit: allocate free beds to waiting patients on every refresh");
    private final Slider threshold = new Slider(50, 100, 90);
    private final Label thresholdValue = Ui.label("90%", "bold");
    private final Spinner<Integer> cleaning = new Spinner<>(0, 240, 15);
    private final Spinner<Integer> refresh = new Spinner<>(2, 60, 5);
    private final VBox errors = new VBox(3);
    private final Label systemInfo = Ui.label("", "muted");
    private boolean dirty;

    public SettingsView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();
        boolean canEdit = manager.hasPermission(Permission.MANAGE_SETTINGS);

        threshold.setMajorTickUnit(10);
        threshold.setShowTickMarks(true);
        threshold.setShowTickLabels(true);
        threshold.valueProperty().addListener((o, a, b) -> {
            thresholdValue.setText(Math.round(b.doubleValue()) + "%");
            dirty = true;
        });
        for (Spinner<Integer> s : java.util.List.of(cleaning, refresh)) {
            s.setEditable(true);
            s.setPrefWidth(100);
            s.focusedProperty().addListener((o, was, is) -> { if (!is) commit(s); });
            s.valueProperty().addListener((o, a, b) -> dirty = true);
        }
        autoAdmit.selectedProperty().addListener((o, a, b) -> dirty = true);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.add(autoAdmit, 0, 0, 2, 1);
        grid.addRow(1, Ui.label("Occupancy alert threshold", "form-label"), Ui.row(10, threshold, thresholdValue));
        grid.addRow(2, Ui.label("Bed cleaning time (min)", "form-label"),
                Ui.row(8, cleaning, Ui.label("used by the time-to-bed prediction", "hint")));
        grid.addRow(3, Ui.label("Refresh interval (s)", "form-label"), refresh);
        grid.setDisable(!canEdit);

        VBox settingsCard = Ui.card("Automation, alerts and display", grid,
                Ui.row(10,
                        disabledUnless(Ui.button("Save Settings", "btn-primary", this::save), canEdit),
                        disabledUnless(Ui.button("Restore Defaults", "btn-secondary", () -> load(AppSettings.defaults(), true)), canEdit),
                        disabledUnless(Ui.button("Discard Changes", "btn-secondary", () -> load(manager.settings(), false)), canEdit)),
                errors);
        if (!canEdit) settingsCard.getChildren().add(Ui.label("Only administrators can change settings.", "muted"));

        GridPane keys = new GridPane();
        keys.setHgap(20);
        keys.setVgap(6);
        String[][] shortcuts = {
                {"Ctrl+1 ... Ctrl+9", "Switch screens in sidebar order"},
                {"Ctrl+N", "New patient intake"},
                {"Ctrl+F", "Patient records search"},
                {"Ctrl+Shift+A", "Admit next patient"},
                {"F5", "Refresh now"},
                {"Ctrl+L", "Log out"}};
        for (int i = 0; i < shortcuts.length; i++) {
            keys.addRow(i, Ui.label(shortcuts[i][0], "bold"), Ui.label(shortcuts[i][1]));
        }

        VBox content = new VBox(16, settingsCard, Ui.card("Keyboard shortcuts", keys),
                Ui.card("System information", systemInfo));
        content.setPadding(new Insets(20, 22, 22, 22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        load(manager.settings(), false);
    }

    private static javafx.scene.control.Button disabledUnless(javafx.scene.control.Button b, boolean allowed) {
        b.setDisable(!allowed);
        return b;
    }

    private void load(AppSettings s, boolean markDirty) {
        autoAdmit.setSelected(s.autoAdmit());
        threshold.setValue(s.highOccupancyThreshold() * 100);
        cleaning.getValueFactory().setValue(s.cleaningMinutes());
        refresh.getValueFactory().setValue(s.refreshSeconds());
        errors.getChildren().clear();
        dirty = markDirty;
    }

    private void save() {
        commit(cleaning);
        commit(refresh);
        AppSettings current = manager.settings();
        AppSettings updated = new AppSettings(autoAdmit.isSelected(), Math.round(threshold.getValue()) / 100.0,
                current.darkMode(), refresh.getValue(), cleaning.getValue());
        OperationResult r = manager.updateSettings(updated);
        errors.getChildren().clear();
        r.errors().forEach(e -> errors.getChildren().add(Ui.label("•  " + e, "error-text")));
        if (r.success()) {
            dirty = false;
            ctx.settingsChanged();
        }
        ctx.showResult(r);
    }

    private static void commit(Spinner<Integer> s) {
        try {
            s.getValueFactory().setValue(Integer.parseInt(s.getEditor().getText().trim()));
        } catch (NumberFormatException ignored) {
            // keep previous value
        }
        s.getEditor().setText(String.valueOf(s.getValue()));
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        if (!dirty) load(manager.settings(), false);
        StaffUser u = manager.currentUser();
        systemInfo.setText("Database: " + manager.databaseDescription()
                + "\nLogged in: " + (u == null ? "-" : u.getFullName() + " (" + u.getRole().getDisplayName() + ")")
                + "\nJava " + System.getProperty("java.version")
                + "  |  JavaFX " + System.getProperty("javafx.runtime.version")
                + "\nBeds: " + manager.allBeds().size() + "  |  Doctors: " + manager.allDoctors().size());
    }
}
