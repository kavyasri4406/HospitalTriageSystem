package com.hospital.ui.views;

import com.hospital.model.Bed;
import com.hospital.model.Doctor;
import com.hospital.model.Patient;
import com.hospital.model.PatientStatus;
import com.hospital.model.TriageLevel;
import com.hospital.model.VitalsRecord;
import com.hospital.persistence.dao.AdmissionLogDAO.Action;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.service.HospitalManager;
import com.hospital.ui.Page;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Searchable register of every patient ever registered (waiting, admitted, discharged or left
 * without being seen). The detail panel shows the patient's dispositions, vitals history with a
 * HR / SpO2 trend chart, and the full audit timeline.
 */
public class PatientRecordsView implements View {

    private static final int SEARCH_LIMIT = 200;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final String ALL_STATUSES = "All statuses";
    private static final String HR_COLOR = "#dc2626";
    private static final String SPO2_COLOR = "#2563eb";
    private static final String NO_SELECTION = "none";
    private static final double VITALS_ROW_HEIGHT = 26;
    /** The vitals table scrolls (to the newest reading) beyond this many rows. */
    private static final int VITALS_VISIBLE_ROWS = 6;

    private final UiContext ctx;
    private final HospitalManager manager;
    private final GridPane root = new GridPane();
    private final TextField search = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final Label count = Ui.label("", "muted");
    private final Label emptyTable = Ui.label("", "muted");
    private final ObservableList<Patient> results = FXCollections.observableArrayList();
    private final FilteredList<Patient> filtered = new FilteredList<>(results);
    private final SortedList<Patient> sorted = new SortedList<>(filtered);
    private final TableView<Patient> table = new TableView<>(sorted);
    private final VBox detail = new VBox(16);
    private final ScrollPane detailScroll = new ScrollPane(detail);
    private final PauseTransition searchDelay = new PauseTransition(javafx.util.Duration.millis(200));

    private LocalDateTime now;
    private String searchError;
    /** True while the list or filter is changed programmatically, so selection events are ignored. */
    private boolean restoring;
    /** Fingerprints of what the table / detail panel currently show; unchanged data is not re-rendered. */
    private String resultsKey = "";
    private String detailKey = "";
    private int detailPatientId = -1;
    /** Updates the time-dependent labels ("waiting 42 min") in place, without rebuilding the panel. */
    private Runnable clockUpdater = () -> {};

    public PatientRecordsView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();
        this.now = manager.now();
        buildTable();

        search.setPromptText("Search by name or MRN");
        // the field is focused on arrival and Modena hides prompt text on focus: keep it visible
        search.setStyle("-fx-prompt-text-fill: derive(-fx-control-inner-background, -35%);");
        search.setTextFormatter(new TextFormatter<String>(PatientRecordsView::limitLength));
        search.textProperty().addListener((o, a, b) -> searchDelay.playFromStart());
        search.setOnAction(e -> {
            searchDelay.stop();
            runSearch();
            if (table.getSelectionModel().isEmpty() && !sorted.isEmpty()) table.getSelectionModel().select(0);
            updateDetail();
            if (!sorted.isEmpty()) table.requestFocus();
        });
        search.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) search.clear(); });
        searchDelay.setOnFinished(e -> { runSearch(); updateDetail(); });
        HBox.setHgrow(search, Priority.ALWAYS);

        statusFilter.getItems().add(ALL_STATUSES);
        for (PatientStatus s : PatientStatus.values()) statusFilter.getItems().add(statusText(s));
        statusFilter.setValue(ALL_STATUSES);
        statusFilter.valueProperty().addListener((o, a, b) -> applyFilter());
        statusFilter.setMinWidth(Region.USE_PREF_SIZE);

        Button clear = Ui.button("Clear", "btn-secondary", () -> {
            search.clear();
            statusFilter.setValue(ALL_STATUSES);
        });
        clear.setMinWidth(Region.USE_PREF_SIZE);

        count.setWrapText(true);
        count.setMaxWidth(Double.MAX_VALUE);
        VBox left = new VBox(10, Ui.row(10, search, statusFilter, clear), count, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        left.setMinWidth(0);

        detail.setPadding(new Insets(0, 6, 6, 0)); // room for the card shadows inside the viewport
        detailScroll.setFitToWidth(true);
        detailScroll.setMaxHeight(Double.MAX_VALUE);

        ColumnConstraints listColumn = new ColumnConstraints();
        listColumn.setPercentWidth(55);
        ColumnConstraints detailColumn = new ColumnConstraints();
        detailColumn.setPercentWidth(45);
        RowConstraints fullHeight = new RowConstraints();
        fullHeight.setVgrow(Priority.ALWAYS);
        root.getColumnConstraints().addAll(listColumn, detailColumn);
        root.getRowConstraints().add(fullHeight);
        root.add(left, 0, 0);
        root.add(detailScroll, 1, 0);
        root.setHgap(16);
        root.setPadding(new Insets(20, 22, 22, 22));

        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> { if (!restoring) updateDetail(); });
        // Ctrl+F lands on this page: put the cursor straight into the search box
        root.sceneProperty().addListener((o, was, is) -> { if (is != null) Platform.runLater(search::requestFocus); });
        showPlaceholder();
    }

    private void buildTable() {
        TableColumn<Patient, TriageLevel> esi = new TableColumn<>("ESI");
        esi.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getTriageLevel()));
        esi.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(TriageLevel level, boolean empty) {
                super.updateItem(level, empty);
                setText(empty || level != null ? null : "-");
                setGraphic(empty || level == null ? null : Ui.esiBadge(level));
            }
        });
        fixedWidth(esi, 62);

        TableColumn<Patient, PatientStatus> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getStatus()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(PatientStatus s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                setGraphic(empty || s == null ? null : statusPill(s, true));
            }
        });
        fixedWidth(status, 96);

        TableColumn<Patient, LocalDateTime> arrived = new TableColumn<>("Arrived");
        arrived.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getIntakeTime()));
        arrived.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDateTime t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.format(Ui.DATE_TIME));
            }
        });
        fixedWidth(arrived, 92);

        TableColumn<Patient, String> mrn = text("MRN", 128, Patient::getMrn);
        mrn.setMinWidth(90);
        TableColumn<Patient, String> name = text("Name", 150, Patient::getFullName);
        name.setMinWidth(90);
        TableColumn<Patient, String> ageSex = text("Age/Sex", 62, PatientRecordsView::ageSex);
        ageSex.setMaxWidth(80);
        TableColumn<Patient, String> bed = text("Bed", 64,
                p -> p.getStatus() == PatientStatus.ADMITTED && p.getAssignedBedId() != null ? p.getAssignedBedId() : "-");
        bed.setMaxWidth(90);

        table.getColumns().add(mrn);
        table.getColumns().add(name);
        table.getColumns().add(ageSex);
        table.getColumns().add(esi);
        table.getColumns().add(status);
        table.getColumns().add(arrived);
        table.getColumns().add(bed);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        emptyTable.setWrapText(true);
        table.setPlaceholder(emptyTable);
        sorted.comparatorProperty().bind(table.comparatorProperty());
    }

    private static void fixedWidth(TableColumn<?, ?> col, double width) {
        col.setPrefWidth(width);
        col.setMinWidth(width);
        col.setMaxWidth(width + 30);
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
        runSearch();
        updateDetail();
    }

    // =====================================================================
    // Search, filter and selection
    // =====================================================================

    /** Search text as sent to the database: trimmed, at most 100 characters, never null. */
    static String normalizeQuery(String text) {
        if (text == null) return "";
        String q = text.strip();
        return q.length() > MAX_QUERY_LENGTH ? q.substring(0, MAX_QUERY_LENGTH).strip() : q;
    }

    /** TextFormatter filter that cuts typed or pasted text so the field never exceeds the query limit. */
    private static TextFormatter.Change limitLength(TextFormatter.Change change) {
        int newLength = change.getControlNewText().length();
        if (newLength <= MAX_QUERY_LENGTH) return change;
        int keep = change.getText().length() - (newLength - MAX_QUERY_LENGTH);
        if (keep <= 0) return null;
        change.setText(change.getText().substring(0, keep));
        int caret = change.getRangeStart() + keep;
        change.selectRange(caret, caret);
        return change;
    }

    /** Re-runs the current search; the table is only touched when the results actually changed. */
    private void runSearch() {
        List<Patient> found;
        try {
            found = manager.searchPatients(normalizeQuery(search.getText()), SEARCH_LIMIT);
            searchError = null;
        } catch (RuntimeException e) {
            found = List.of();
            searchError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String key = resultsKey(found);
        if (!key.equals(resultsKey)) {
            Integer keep = selectedId();
            restoring = true;
            try {
                results.setAll(found);
                select(keep);
            } finally {
                restoring = false;
            }
            resultsKey = key;
        }
        updateCount();
    }

    private void applyFilter() {
        String wanted = statusFilter.getValue();
        Predicate<Patient> match = wanted == null || ALL_STATUSES.equals(wanted)
                ? p -> true
                : p -> statusText(p.getStatus()).equals(wanted);
        Integer keep = selectedId();
        restoring = true;
        try {
            filtered.setPredicate(match);
            select(keep);
        } finally {
            restoring = false;
        }
        updateCount();
        updateDetail();
    }

    /** Every column the table shows that can change while a patient is in the department. */
    private static String resultsKey(List<Patient> patients) {
        StringBuilder sb = new StringBuilder(patients.size() * 16);
        for (Patient p : patients) {
            sb.append(p.getId()).append(':').append(p.getStatus())
              .append(':').append(p.getTriageLevel() == null ? 0 : p.getTriageLevel().getEsi())
              .append(':').append(p.getAssignedBedId()).append(';');
        }
        return sb.toString();
    }

    private Integer selectedId() {
        Patient p = table.getSelectionModel().getSelectedItem();
        return p == null ? null : p.getId();
    }

    private void select(Integer patientId) {
        if (patientId != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).getId() == patientId) {
                    table.getSelectionModel().select(i);
                    return;
                }
            }
        }
        table.getSelectionModel().clearSelection();
    }

    /** Updates the result count above the table and the message the empty table shows. */
    private void updateCount() {
        if (searchError != null) {
            count.getStyleClass().setAll("label", "error-text");
            count.setText("Search failed: " + searchError);
            emptyTable.setText("The patient records could not be searched.");
            return;
        }
        count.getStyleClass().setAll("label", "muted");
        int total = results.size();
        boolean query = !normalizeQuery(search.getText()).isEmpty();
        boolean allStatuses = ALL_STATUSES.equals(statusFilter.getValue());
        String noun = !query
                ? (total == 1 ? "patient on record" : "patients on record")
                : (total == 1 ? "match" : "matches");
        String text = allStatuses
                ? total + " " + noun
                : "Showing " + sorted.size() + " of " + total + " " + noun;
        if (total >= SEARCH_LIMIT) text += "  -  only the " + SEARCH_LIMIT + " most recent are listed, refine the search";
        count.setText(text);

        if (total > 0 && !allStatuses) {
            emptyTable.setText("No " + (query ? "matches" : "patients on record") + " with status "
                    + statusFilter.getValue() + ".");
        } else {
            emptyTable.setText(query ? "No patients match the search." : "No patients have been registered yet.");
        }
    }

    // =====================================================================
    // Detail panel
    // =====================================================================

    /** Shows the selected patient; the panel is rebuilt only when their data changed, so the scroll stays put. */
    private void updateDetail() {
        now = manager.now();
        Patient p = table.getSelectionModel().getSelectedItem();
        if (p == null) {
            showPlaceholder();
            return;
        }
        List<VitalsRecord> history;
        List<LogEntry> timeline;
        try {
            history = manager.vitalsHistory(p.getId());
            timeline = manager.patientTimeline(p.getId());
        } catch (RuntimeException e) {
            Label error = Ui.label("Could not load the record of " + p.getFullName() + ": " + e.getMessage(), "error-text");
            error.setWrapText(true);
            detail.getChildren().setAll(Ui.card("Patient record", error));
            detailKey = "error";
            detailPatientId = p.getId();
            clockUpdater = () -> {};
            return;
        }
        String key = p.getId() + "|" + p.getStatus() + "|" + p.getTriageLevel() + "|" + p.getAssignedBedId()
                + "|" + p.getAssignedDoctorId() + "|" + p.isOverdue(now) + "|" + history.size() + "|" + timeline.size();
        if (!key.equals(detailKey)) {
            boolean otherPatient = p.getId() != detailPatientId;
            clockUpdater = () -> {};
            detail.getChildren().setAll(summaryCard(p), vitalsCard(p, history), timelineCard(timeline));
            detailKey = key;
            detailPatientId = p.getId();
            if (otherPatient) detailScroll.setVvalue(0);
        }
        clockUpdater.run();
    }

    private void showPlaceholder() {
        if (NO_SELECTION.equals(detailKey)) return;
        Label hint = Ui.label("Select a patient to see their full record: arrival and disposition times, "
                + "bed and doctor, vitals history with a trend chart, and every event in their timeline.", "muted");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);
        detail.getChildren().setAll(Ui.card("Patient record", hint));
        detailKey = NO_SELECTION;
        detailPatientId = -1;
        clockUpdater = () -> {};
    }

    private VBox summaryCard(Patient p) {
        PatientStatus status = p.getStatus();
        Label name = Ui.label(p.getFullName(), "section-title");
        name.setWrapText(true);
        Label ids = Ui.label(p.getMrn() + "  |  " + p.getAge() + " y, " + p.getGender() + "  |  "
                + p.getCategory().toLowerCase(Locale.ROOT), "muted");
        ids.setWrapText(true);

        HBox badges = Ui.row(8);
        TriageLevel level = p.getTriageLevel();
        if (level != null) {
            Label esi = Ui.label("ESI " + level.getEsi() + "  " + level.getLabel(), "esi-badge");
            esi.setStyle(Ui.esiStyle(level));
            esi.setMinWidth(Region.USE_PREF_SIZE);
            badges.getChildren().add(esi);
        }
        badges.getChildren().add(statusPill(status, false));
        if (p.isOverdue(now)) badges.getChildren().add(Ui.pill("OVERDUE", "red"));

        Label complaint = Ui.label(p.getChiefComplaint() + (p.isTrauma() ? "  (trauma)" : ""), "bold");
        complaint.setWrapText(true);
        Label scores = Ui.label(String.format(Locale.ROOT, "Severity %.1f  |  expected resources %d",
                p.getSeverityScore(), p.getExpectedResources()), "muted", "small");

        GridPane facts = new GridPane();
        facts.setHgap(14);
        facts.setVgap(6);
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        facts.getColumnConstraints().addAll(keys, values);

        LocalDateTime arrived = p.getIntakeTime();
        LocalDateTime admitted = p.getAdmittedTime();
        LocalDateTime closed = p.getDischargedTime();
        fact(facts, "Arrived", arrived.format(Ui.DATE_TIME));
        if (admitted != null) {
            fact(facts, "Admitted", admitted.format(Ui.DATE_TIME) + "  (after " + Ui.minutes(minutesBetween(arrived, admitted)) + " waiting)");
        }
        if (closed != null) {
            fact(facts, status == PatientStatus.LEFT_WITHOUT_BEING_SEEN ? "Left" : "Discharged", closed.format(Ui.DATE_TIME));
        }
        switch (status) {
            case WAITING -> {
                Label waiting = fact(facts, "Waiting", "");
                int target = level == null ? 0 : level.getTargetWaitMinutes();
                clockUpdater = () -> waiting.setText(Ui.minutes(minutesBetween(arrived, now)) + "  (target " + target + " min)");
            }
            case ADMITTED -> {
                Label stay = fact(facts, "In department", "");
                clockUpdater = () -> stay.setText(Ui.minutes(minutesBetween(arrived, now)) + " so far");
            }
            case DISCHARGED -> fact(facts, "Length of stay", Ui.minutes(minutesBetween(arrived, closed)));
            case LEFT_WITHOUT_BEING_SEEN -> fact(facts, "Waited", Ui.minutes(minutesBetween(arrived, closed)) + " before leaving");
        }

        String bedId = p.getAssignedBedId();
        if (status == PatientStatus.WAITING) {
            fact(facts, "Bed", "Not assigned yet - needs " + manager.idealBedType(p).getDisplayName());
        } else if (bedId != null) {
            Bed bed = manager.findBed(bedId);
            fact(facts, status == PatientStatus.ADMITTED ? "Bed" : "Last bed",
                    bed == null ? bedId : bedId + "  (" + bed.getType().getDisplayName() + ", " + bed.getWardName() + ")");
        }
        if (status == PatientStatus.ADMITTED || status == PatientStatus.DISCHARGED) {
            Doctor d = manager.findDoctor(p.getAssignedDoctorId());
            Label doctor = fact(facts, status == PatientStatus.ADMITTED ? "Doctor" : "Treated by",
                    d == null ? "No doctor assigned" : d.getName() + "  (" + d.getSpecialty().getDisplayName() + ")");
            if (d == null && status == PatientStatus.ADMITTED) doctor.getStyleClass().add("error-text");
        }

        VBox card = Ui.card(null, name, ids, badges, complaint, scores, facts);
        if (status == PatientStatus.WAITING) {
            card.getChildren().add(Ui.row(10, Ui.button("Show in Queue Board", "btn-primary", () -> ctx.navigate(Page.QUEUE))));
        } else if (status == PatientStatus.ADMITTED) {
            card.getChildren().add(Ui.row(10, Ui.button("Show in Bed Grid", "btn-primary", () -> ctx.navigate(Page.BEDS))));
        }
        return card;
    }

    /** Adds a "key   value" row to the facts grid and returns the value label. */
    private static Label fact(GridPane grid, String key, String value) {
        int row = grid.getRowCount();
        Label k = Ui.label(key, "muted");
        k.setMinWidth(Region.USE_PREF_SIZE);
        Label v = Ui.label(value);
        v.setWrapText(true);
        v.setMaxWidth(Double.MAX_VALUE);
        grid.add(k, 0, row);
        grid.add(v, 1, row);
        return v;
    }

    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return 0;
        return Math.max(0, Duration.between(from, to).toMinutes());
    }

    private VBox vitalsCard(Patient p, List<VitalsRecord> history) {
        VBox card = Ui.card(null, cardHeader("Vitals history", history.size() + (history.size() == 1 ? " record" : " records")));
        if (history.isEmpty()) {
            card.getChildren().add(Ui.label("No vitals recorded.", "muted"));
            return card;
        }
        boolean oneDay = history.stream().map(r -> r.recordedAt().toLocalDate()).distinct().count() <= 1;
        DateTimeFormatter timeFormat = oneDay ? Ui.TIME : Ui.DATE_TIME;
        Label hint = Ui.label("Values in red are outside the ESI thresholds for this age group.", "hint");
        hint.setWrapText(true);
        card.getChildren().addAll(vitalsTable(p, history, timeFormat), hint);
        if (history.size() >= 2) {
            card.getChildren().addAll(
                    Ui.row(16, legendItem("Heart rate (bpm)", HR_COLOR), legendItem("SpO2 (%)", SPO2_COLOR)),
                    trendChart(history, timeFormat));
        } else {
            card.getChildren().add(Ui.label("A trend chart appears once the patient has been re-assessed.", "hint"));
        }
        return card;
    }

    private static TableView<VitalsRecord> vitalsTable(Patient p, List<VitalsRecord> history, DateTimeFormatter timeFormat) {
        TableView<VitalsRecord> t = new TableView<>(FXCollections.observableArrayList(history));
        t.getColumns().add(vitalsColumn("Time", timeFormat == Ui.TIME ? 46 : 84, r -> r.recordedAt().format(timeFormat), r -> false));
        t.getColumns().add(vitalsColumn("HR", 36, r -> String.valueOf(r.vitals().heartRate()),
                r -> r.vitals().heartRate() > p.dangerZoneHeartRate() || r.vitals().heartRate() < 40));
        t.getColumns().add(vitalsColumn("BP", 56, r -> r.vitals().bloodPressure(), r -> r.vitals().systolicBp() < 90));
        t.getColumns().add(vitalsColumn("RR", 32, r -> String.valueOf(r.vitals().respiratoryRate()),
                r -> r.vitals().respiratoryRate() > p.dangerZoneRespiratoryRate() || r.vitals().respiratoryRate() < 8));
        t.getColumns().add(vitalsColumn("SpO2", 42, r -> r.vitals().spo2() + "%", r -> r.vitals().spo2() < 92));
        t.getColumns().add(vitalsColumn("Temp", 40, r -> String.format(Locale.ROOT, "%.1f", r.vitals().temperature()),
                r -> r.vitals().temperature() >= 39.5 || r.vitals().temperature() < 35.0));
        t.getColumns().add(vitalsColumn("Pain", 36, r -> String.valueOf(r.vitals().painScore()), r -> r.vitals().painScore() >= 8));
        t.getColumns().add(vitalsColumn("GCS", 36, r -> String.valueOf(r.vitals().gcs()), r -> r.vitals().gcs() < 14));

        TableColumn<VitalsRecord, TriageLevel> esi = new TableColumn<>("ESI");
        esi.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().level()));
        esi.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(TriageLevel level, boolean empty) {
                super.updateItem(level, empty);
                setText(null);
                if (empty || level == null) { setGraphic(null); return; }
                Label badge = Ui.label(String.valueOf(level.getEsi()), "esi-badge");
                badge.setStyle(Ui.esiStyle(level));
                badge.setMinWidth(Region.USE_PREF_SIZE);
                setGraphic(badge);
            }
        });
        esi.setPrefWidth(38);
        esi.setStyle("-fx-alignment: CENTER;");
        t.getColumns().add(esi);
        t.getColumns().add(vitalsColumn("Sev.", 38, r -> String.format(Locale.ROOT, "%.1f", r.severityScore()), r -> false));
        t.getColumns().add(vitalsColumn("By", 56, r -> r.recordedBy() == null ? "-" : r.recordedBy(), r -> false));
        t.getColumns().forEach(c -> { c.setSortable(false); c.setReorderable(false); });

        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        t.setStyle("-fx-font-size: 11.5px;");
        t.setFixedCellSize(VITALS_ROW_HEIGHT);
        boolean scrolls = history.size() > VITALS_VISIBLE_ROWS;
        fitToRows(t, Math.min(history.size(), VITALS_VISIBLE_ROWS), scrolls);
        if (scrolls) {
            t.scrollTo(history.size() - 1);
            // a scrollTo is measured against the current height: repeat it once the final height is laid out,
            // otherwise the newest reading ends up half hidden below a sliver of an older one
            t.heightProperty().addListener((o, a, h) -> Platform.runLater(() -> t.scrollTo(t.getItems().size() - 1)));
        }
        return t;
    }

    /**
     * Makes a fixed-cell-size table exactly {@code rows} rows tall, so no half empty row shows below the data.
     * The header height is only known once the skin has laid it out, so the first size is an estimate.
     *
     * @param scrolls true when the table holds more than {@code rows} items and so scrolls anyway
     */
    private static void fitToRows(TableView<?> t, int rows, boolean scrolls) {
        double body = rows * t.getFixedCellSize();
        // +1 so rounding on scaled displays never brings up a vertical scroll bar when every row fits
        double slack = scrolls ? 0 : 1;
        setFixedHeight(t, 26 + body + 4);
        t.skinProperty().addListener((o, was, skin) -> {
            if (t.lookup(".column-header-background") instanceof Region header) {
                header.heightProperty().addListener((obs, a, h) -> setFixedHeight(t,
                        h.doubleValue() + body + t.snappedTopInset() + t.snappedBottomInset() + slack));
            }
        });
    }

    private static void setFixedHeight(Region r, double height) {
        r.setMinHeight(height);
        r.setPrefHeight(height);
        r.setMaxHeight(height);
    }

    /** A centred text column of the vitals table; cells matching {@code danger} are shown in red. */
    private static TableColumn<VitalsRecord, VitalsRecord> vitalsColumn(String title, double width,
                                                                       Function<VitalsRecord, String> text,
                                                                       Predicate<VitalsRecord> danger) {
        TableColumn<VitalsRecord, VitalsRecord> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(VitalsRecord r, boolean empty) {
                super.updateItem(r, empty);
                getStyleClass().removeAll("error-text", "bold");
                if (empty || r == null) { setText(null); return; }
                setText(text.apply(r));
                if (danger.test(r)) getStyleClass().addAll("error-text", "bold");
            }
        });
        col.setPrefWidth(width);
        col.setStyle("-fx-alignment: CENTER;");
        return col;
    }

    private static Node trendChart(List<VitalsRecord> history, DateTimeFormatter timeFormat) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        x.setAnimated(false);
        y.setAnimated(false);
        y.setForceZeroInRange(false);
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setMinHeight(220);
        chart.setPrefHeight(220);
        chart.setMaxHeight(220);

        XYChart.Series<String, Number> hr = new XYChart.Series<>();
        hr.setName("Heart rate");
        XYChart.Series<String, Number> spo2 = new XYChart.Series<>();
        spo2.setName("SpO2");
        Set<String> used = new HashSet<>();
        for (VitalsRecord r : history) {
            // a category axis merges equal labels, so two readings in the same minute get a suffix
            String label = r.recordedAt().format(timeFormat);
            String category = label;
            for (int n = 2; !used.add(category); n++) category = label + " #" + n;
            hr.getData().add(new XYChart.Data<>(category, r.vitals().heartRate()));
            spo2.getData().add(new XYChart.Data<>(category, r.vitals().spo2()));
        }
        chart.getData().add(hr);
        chart.getData().add(spo2);
        colour(hr, HR_COLOR, " bpm");
        colour(spo2, SPO2_COLOR, "%");
        return chart;
    }

    /** Fixed series colours (the custom legend matches them) plus a tooltip on every point. */
    private static void colour(XYChart.Series<String, Number> series, String color, String unit) {
        if (series.getNode() != null) series.getNode().setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2px;");
        for (XYChart.Data<String, Number> d : series.getData()) {
            if (d.getNode() == null) continue;
            d.getNode().setStyle("-fx-background-color: " + color + ", white;");
            Tooltip.install(d.getNode(), new Tooltip(series.getName() + " " + d.getYValue() + unit + " at " + d.getXValue()));
        }
    }

    private static HBox legendItem(String text, String color) {
        Region swatch = new Region();
        swatch.setMinSize(16, 4);
        swatch.setMaxSize(16, 4);
        swatch.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 2;");
        return Ui.row(6, swatch, Ui.label(text, "small"));
    }

    private static VBox timelineCard(List<LogEntry> timeline) {
        VBox card = Ui.card(null, cardHeader("Timeline", timeline.size() + (timeline.size() == 1 ? " event" : " events")));
        if (timeline.isEmpty()) {
            card.getChildren().add(Ui.label("No events recorded.", "muted"));
            return card;
        }
        for (int i = 0; i < timeline.size(); i++) {
            if (i > 0) card.getChildren().add(new Separator());
            card.getChildren().add(timelineRow(timeline.get(i)));
        }
        return card;
    }

    private static VBox timelineRow(LogEntry e) {
        Label time = Ui.label(e.createdAt() == null ? "-" : e.createdAt().format(Ui.DATE_TIME), "bold", "small");
        time.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = Ui.row(8, time, actionPill(e.action()), Ui.hgrow());
        if (e.performedBy() != null && !e.performedBy().isBlank()) {
            Label by = Ui.label("by " + e.performedBy(), "muted", "small");
            by.setMinWidth(Region.USE_PREF_SIZE);
            top.getChildren().add(by);
        }
        VBox row = new VBox(4, top);
        if (e.details() != null && !e.details().isBlank()) {
            Label details = Ui.label(e.details(), "small");
            details.setWrapText(true);
            details.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(details);
        }
        return row;
    }

    private static HBox cardHeader(String title, String note) {
        Label n = Ui.label(note, "muted", "small");
        n.setMinWidth(Region.USE_PREF_SIZE);
        return Ui.row(8, Ui.label(title, "card-title"), Ui.hgrow(), n);
    }

    // =====================================================================
    // Labels and pills
    // =====================================================================

    private static String ageSex(Patient p) {
        String g = p.getGender() == null ? "" : p.getGender();
        return p.getAge() + (g.isEmpty() ? "" : " " + g.charAt(0));
    }

    private static String statusText(PatientStatus s) {
        return switch (s) {
            case WAITING -> "Waiting";
            case ADMITTED -> "Admitted";
            case DISCHARGED -> "Discharged";
            case LEFT_WITHOUT_BEING_SEEN -> "Left without being seen";
        };
    }

    /** @param compact shortens "Left without being seen" for the narrow table column */
    private static Label statusPill(PatientStatus s, boolean compact) {
        String color = switch (s) {
            case WAITING -> "amber";
            case ADMITTED -> "blue";
            case DISCHARGED -> "green";
            case LEFT_WITHOUT_BEING_SEEN -> "red";
        };
        String text = compact && s == PatientStatus.LEFT_WITHOUT_BEING_SEEN ? "Left unseen" : statusText(s);
        return Ui.pill(text, color);
    }

    private static Label actionPill(String action) {
        Action a = null;
        try {
            if (action != null) a = Action.valueOf(action);
        } catch (IllegalArgumentException unknown) {
            // older or newer log rows: shown as plain text below
        }
        if (a == null) return Ui.pill(action == null ? "-" : action.replace('_', ' '), "grey");
        return switch (a) {
            case REGISTERED -> Ui.pill("Registered", "grey");
            case REASSESSED -> Ui.pill("Re-assessed", "amber");
            case ADMITTED -> Ui.pill("Admitted", "green");
            case TRANSFERRED -> Ui.pill("Transferred", "blue");
            case DISCHARGED -> Ui.pill("Discharged", "blue");
            case LEFT_WITHOUT_BEING_SEEN -> Ui.pill("Left without being seen", "red");
            case BED_STATUS -> Ui.pill("Bed status", "grey");
        };
    }
}
