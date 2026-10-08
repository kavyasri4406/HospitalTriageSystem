package com.hospital.ui.views;

import com.hospital.algorithms.TriageAssessment;
import com.hospital.model.PatientIntakeForm;
import com.hospital.model.PatientFactory;
import com.hospital.model.Permission;
import com.hospital.model.TriageLevel;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.service.ValidationResult;
import com.hospital.ui.Page;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Intake form with live ESI preview while the nurse types. */
public class PatientFormView implements View {

    private static final String[] COMMON_COMPLAINTS = {
            "Chest pain", "Shortness of breath", "Abdominal pain", "Head injury", "Fever", "Fall",
            "Laceration", "Seizure", "Stroke symptoms", "Allergic reaction / anaphylaxis", "Overdose",
            "Back pain", "Sore throat", "Vomiting and diarrhoea", "Fracture", "Burn", "Dizziness"
    };

    private final UiContext ctx;
    private final HospitalManager manager;
    private final ScrollPane root;

    private final TextField name = new TextField();
    private final Spinner<Integer> age = intSpinner(0, 120, 35);
    private final ComboBox<String> gender = new ComboBox<>();
    private final ComboBox<String> complaint = new ComboBox<>();
    private final CheckBox trauma = new CheckBox("Traumatic injury (accident, fall, assault)");
    private final Spinner<Integer> heartRate = intSpinner(20, 250, 80);
    private final Spinner<Integer> systolic = intSpinner(40, 260, 120);
    private final Spinner<Integer> diastolic = intSpinner(20, 160, 80);
    private final Spinner<Integer> respRate = intSpinner(4, 70, 16);
    private final Spinner<Integer> spo2 = intSpinner(50, 100, 98);
    private final Spinner<Double> temperature = doubleSpinner(30.0, 44.0, 37.0);
    private final Slider pain = new Slider(0, 10, 0);
    private final Label painValue = Ui.label("0 / 10", "bold");
    private final Spinner<Integer> gcs = intSpinner(3, 15, 15);
    private final Spinner<Integer> resources = intSpinner(0, 5, 1);

    private final VBox errors = new VBox(3);
    private final VBox preview = new VBox(8);

    public PatientFormView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();

        name.setPromptText("e.g. Priya Sharma");
        gender.getItems().addAll("Male", "Female", "Other");
        gender.setValue("Female");
        complaint.getItems().addAll(COMMON_COMPLAINTS);
        complaint.setEditable(true);
        complaint.setPromptText("Select or type the presenting complaint");
        complaint.setMaxWidth(Double.MAX_VALUE);
        pain.setMajorTickUnit(1);
        pain.setMinorTickCount(0);
        pain.setSnapToTicks(true);
        pain.setShowTickMarks(true);
        pain.setShowTickLabels(true);
        pain.valueProperty().addListener((o, a, b) -> painValue.setText(b.intValue() + " / 10"));

        GridPane demographics = formGrid();
        int r = 0;
        demographics.addRow(r++, formLabel("Full name"), name);
        demographics.addRow(r++, formLabel("Age (years)"), age);
        demographics.addRow(r++, formLabel("Gender"), gender);
        demographics.addRow(r++, formLabel("Chief complaint"), complaint);
        demographics.add(trauma, 1, r);

        GridPane vitals = formGrid();
        r = 0;
        vitals.addRow(r++, formLabel("Heart rate (bpm)"), heartRate);
        vitals.addRow(r++, formLabel("Blood pressure (mmHg)"), Ui.row(6, systolic, Ui.label("/"), diastolic));
        vitals.addRow(r++, formLabel("Respiratory rate (/min)"), respRate);
        vitals.addRow(r++, formLabel("SpO2 (%)"), spo2);
        vitals.addRow(r++, formLabel("Temperature (°C)"), temperature);
        vitals.addRow(r++, formLabel("Pain score"), Ui.row(10, pain, painValue));
        HBox.setHgrow(pain, Priority.ALWAYS);
        vitals.addRow(r++, formLabel("GCS (3-15)"), Ui.row(8, gcs, Ui.label("15 = fully alert, <= 8 = unresponsive", "hint")));
        vitals.addRow(r++, formLabel("Expected resources"), Ui.row(8, resources,
                Ui.label("labs, imaging, IV fluids, specialist consult, procedure...", "hint")));

        Button register = Ui.button("Register & Triage Patient", "btn-primary", this::submit);
        if (!manager.hasPermission(Permission.REGISTER_PATIENT)) {
            register.setDisable(true);
            register.setTooltip(new Tooltip("Your role cannot register patients"));
        }
        HBox buttons = Ui.row(10,
                register,
                Ui.button("Fill Sample Patient", "btn-secondary", this::fillSample),
                Ui.button("Clear Form", "btn-secondary", this::clear));

        VBox form = new VBox(16,
                Ui.card("Patient details", demographics),
                Ui.card("Vital signs", vitals),
                errors,
                buttons);
        HBox.setHgrow(form, Priority.ALWAYS);
        form.setMaxWidth(Double.MAX_VALUE);

        VBox previewCard = Ui.card("Live ESI triage preview", preview);
        previewCard.setPrefWidth(380);
        previewCard.setMinWidth(340);
        VBox legend = Ui.card("ESI levels and target wait", legendRows());
        legend.setPrefWidth(380);
        VBox side = new VBox(16, previewCard, legend);

        HBox content = new HBox(18, form, side);
        content.setPadding(new Insets(20, 22, 22, 22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);

        // live preview on every change
        name.textProperty().addListener((o, a, b) -> updatePreview());
        complaint.getEditor().textProperty().addListener((o, a, b) -> updatePreview());
        complaint.valueProperty().addListener((o, a, b) -> updatePreview());
        gender.valueProperty().addListener((o, a, b) -> updatePreview());
        trauma.selectedProperty().addListener((o, a, b) -> updatePreview());
        pain.valueProperty().addListener((o, a, b) -> updatePreview());
        for (Spinner<?> s : new Spinner<?>[]{age, heartRate, systolic, diastolic, respRate, spo2, temperature, gcs, resources}) {
            s.valueProperty().addListener((o, a, b) -> updatePreview());
        }
        updatePreview();
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        // The form keeps whatever the user typed; nothing to reload.
    }

    private PatientIntakeForm readForm() {
        String complaintText = complaint.getEditor().getText();
        if (complaintText == null || complaintText.isBlank()) complaintText = complaint.getValue();
        return new PatientIntakeForm(
                name.getText() == null ? "" : name.getText(),
                age.getValue(),
                gender.getValue(),
                complaintText == null ? "" : complaintText,
                trauma.isSelected(),
                heartRate.getValue(), systolic.getValue(), diastolic.getValue(), respRate.getValue(),
                spo2.getValue(), temperature.getValue(), (int) Math.round(pain.getValue()),
                gcs.getValue(), resources.getValue());
    }

    private void updatePreview() {
        PatientIntakeForm form = readForm();
        preview.getChildren().clear();
        ValidationResult v = manager.validate(form);
        if (!v.isValid()) {
            preview.getChildren().add(Ui.label("Complete the form to see the triage result.", "muted"));
            preview.setStyle("");
            return;
        }
        TriageAssessment a = manager.previewTriage(form);
        TriageLevel level = a.level();
        Label badge = Ui.label("ESI " + level.getEsi() + "  " + level.getLabel(), "esi-badge", "esi-badge-large");
        badge.setStyle(Ui.esiStyle(level));
        String category = PatientFactory.create(form, manager.now()).getCategory();

        preview.getChildren().addAll(
                badge,
                Ui.label(String.format("Severity score: %.1f / 100", a.severityScore()), "section-title"),
                Ui.label("Target: seen within " + (level.getTargetWaitMinutes() == 0 ? "0 min (immediately)"
                        : level.getTargetWaitMinutes() + " min"), "bold"),
                Ui.label("Age group: " + category.toLowerCase(), "muted"),
                Ui.label("Why:", "form-label"));
        for (String reason : a.reasons()) {
            Label l = Ui.label("•  " + reason);
            l.setWrapText(true);
            preview.getChildren().add(l);
        }
    }

    private void submit() {
        errors.getChildren().clear();
        commitEditors();
        OperationResult result = manager.registerPatient(readForm());
        if (!result.success()) {
            for (String e : result.errors()) errors.getChildren().add(Ui.label("•  " + e, "error-text"));
            ctx.showResult(result);
            return;
        }
        ctx.showResult(result);
        clear();
        if (result.patient().getTriageLevel().isCritical()) {
            ctx.navigate(Page.QUEUE);
        }
    }

    private void fillSample() {
        PatientIntakeForm f = manager.randomIntakeForm();
        name.setText(f.fullName());
        age.getValueFactory().setValue(f.age());
        gender.setValue(f.gender());
        complaint.getEditor().setText(f.chiefComplaint());
        complaint.setValue(f.chiefComplaint());
        trauma.setSelected(f.trauma());
        heartRate.getValueFactory().setValue(f.heartRate());
        systolic.getValueFactory().setValue(f.systolicBp());
        diastolic.getValueFactory().setValue(f.diastolicBp());
        respRate.getValueFactory().setValue(f.respiratoryRate());
        spo2.getValueFactory().setValue(f.spo2());
        temperature.getValueFactory().setValue(f.temperature());
        pain.setValue(f.painScore());
        gcs.getValueFactory().setValue(f.gcs());
        resources.getValueFactory().setValue(f.expectedResources());
        errors.getChildren().clear();
    }

    private void clear() {
        name.clear();
        age.getValueFactory().setValue(35);
        gender.setValue("Female");
        complaint.setValue(null);
        complaint.getEditor().clear();
        trauma.setSelected(false);
        heartRate.getValueFactory().setValue(80);
        systolic.getValueFactory().setValue(120);
        diastolic.getValueFactory().setValue(80);
        respRate.getValueFactory().setValue(16);
        spo2.getValueFactory().setValue(98);
        temperature.getValueFactory().setValue(37.0);
        pain.setValue(0);
        gcs.getValueFactory().setValue(15);
        resources.getValueFactory().setValue(1);
        errors.getChildren().clear();
        updatePreview();
    }

    private VBox legendRows() {
        VBox rows = new VBox(6);
        for (TriageLevel level : TriageLevel.values()) {
            rows.getChildren().add(Ui.row(8, Ui.esiBadge(level), Ui.label(level.getLabel(), "bold"), Ui.hgrow(),
                    Ui.label(level.getTargetWaitMinutes() == 0 ? "immediate" : "<= " + level.getTargetWaitMinutes() + " min", "muted")));
        }
        return rows;
    }

    private void commitEditors() {
        for (Spinner<?> s : new Spinner<?>[]{age, heartRate, systolic, diastolic, respRate, spo2, temperature, gcs, resources}) {
            commit(s);
        }
    }

    private static <T> void commit(Spinner<T> spinner) {
        if (!spinner.isEditable()) return;
        String text = spinner.getEditor().getText();
        SpinnerValueFactory<T> factory = spinner.getValueFactory();
        try {
            T value = factory.getConverter().fromString(text);
            if (value != null && !value.equals(factory.getValue())) factory.setValue(value);
        } catch (RuntimeException ignored) {
            // leave the previous value
        }
        spinner.getEditor().setText(factory.getConverter().toString(factory.getValue()));
    }

    private static Spinner<Integer> intSpinner(int min, int max, int initial) {
        Spinner<Integer> s = new Spinner<>(min, max, initial);
        s.setEditable(true);
        s.setPrefWidth(110);
        s.focusedProperty().addListener((o, was, is) -> { if (!is) commit(s); });
        return s;
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double initial) {
        Spinner<Double> s = new Spinner<>(min, max, initial, 0.1);
        s.setEditable(true);
        s.setPrefWidth(110);
        s.focusedProperty().addListener((o, was, is) -> { if (!is) commit(s); });
        return s;
    }

    private static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints(170);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private static Label formLabel(String text) {
        Label l = Ui.label(text, "form-label");
        l.setAlignment(Pos.CENTER_LEFT);
        return l;
    }
}
