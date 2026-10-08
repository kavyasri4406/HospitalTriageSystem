package com.hospital.ui.views;

import com.hospital.model.Permission;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.service.ReportService;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shift handover report preview with save, CSV export, copy and print.
 * The preview is a snapshot: it is rebuilt when the screen is shown, on "Refresh Report", or on every
 * refresh while "Auto-refresh" is ticked (keeping the scroll position), so reading is never interrupted.
 */
public class ReportsView implements View {

    /** Newest audit entries included in the activity CSV. */
    private static final int ACTIVITY_EXPORT_LIMIT = 10_000;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private static final double PRINT_FONT_SIZE = 9;
    private static final double MIN_PRINT_FONT_SIZE = 6;

    /** Folder of the last save; static because screens are rebuilt at every login. */
    private static File lastDirectory;
    private static String monospaceFamily;

    private final UiContext ctx;
    private final HospitalManager manager;
    private final ReportService reports;
    private final TextArea preview = new TextArea();
    private final CheckBox autoRefresh = new CheckBox("Auto-refresh");
    private final Label modePill = Ui.pill("SNAPSHOT", "grey");
    private final Label info = Ui.label("", "muted", "small");
    private final Label permissionHint = Ui.label("Your role cannot export or print reports.", "hint");
    private final List<Button> exportButtons = new ArrayList<>();
    private final VBox root;

    /** True when the preview must be rebuilt on the next refresh (first display, or shown again). */
    private boolean stale = true;
    private LocalDateTime generatedAt;

    public ReportsView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();
        this.reports = new ReportService(manager);

        Button refresh = Ui.button("Refresh Report", "btn-primary", this::regenerate);
        Button copy = Ui.button("Copy", "btn-secondary", this::copyToClipboard);
        Button save = Ui.button("Save Report (.txt)", "btn-secondary", this::saveReport);
        Button patients = Ui.button("Export Patients CSV", "btn-secondary", this::exportPatients);
        Button activity = Ui.button("Export Activity CSV", "btn-secondary", this::exportActivity);
        Button print = Ui.button("Print", "btn-secondary", this::print);
        refresh.setTooltip(new Tooltip("Rebuild the report from the current state of the department"));
        autoRefresh.setTooltip(new Tooltip("Rebuild the report on every screen refresh (your scroll position is kept)"));
        copy.setTooltip(new Tooltip("Copy the whole report to the clipboard, e.g. for an e-mail handover"));
        save.setTooltip(new Tooltip("Save the report as a UTF-8 text file"));
        patients.setTooltip(new Tooltip("Every patient ever registered, one row each (UTF-8 CSV, opens in Excel)"));
        activity.setTooltip(new Tooltip("Audit trail of the last " + String.format(Locale.ROOT, "%,d", ACTIVITY_EXPORT_LIMIT)
                + " actions, oldest first, with who performed each one"));
        print.setTooltip(new Tooltip("Print the report on paginated monospaced pages"));
        exportButtons.addAll(List.of(save, patients, activity, print));

        autoRefresh.selectedProperty().addListener((obs, was, on) -> {
            if (on) regenerate();
            else updateInfo();
        });

        HBox toolbar = Ui.row(8, refresh, autoRefresh, Ui.hgrow(), copy, save, patients, activity, print);
        toolbar.getChildren().forEach(n -> ((Region) n).setMinWidth(Region.USE_PREF_SIZE));

        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setStyle("-fx-font-family: \"" + monospaceFamily() + "\"; -fx-font-size: 12.5px;");
        preview.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(preview, Priority.ALWAYS);

        Label title = Ui.label("Shift handover report", "card-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        permissionHint.setMinWidth(Region.USE_PREF_SIZE);
        info.setMinWidth(0);
        HBox header = Ui.row(10, title, modePill, Ui.hgrow(), permissionHint, info);
        VBox card = Ui.card(null, header, preview);
        VBox.setVgrow(card, Priority.ALWAYS);

        root = new VBox(14, toolbar, card);
        root.setPadding(new Insets(20, 22, 22, 22));
        // The shell re-attaches this root on every navigation: rebuild the snapshot when shown again.
        root.parentProperty().addListener((obs, old, parent) -> { if (parent != null) stale = true; });
    }

    @Override
    public Node root() { return root; }

    /** Called on navigation and on every automatic tick: cheap unless the snapshot must be rebuilt. */
    @Override
    public void refresh() {
        boolean canExport = manager.hasPermission(Permission.EXPORT_REPORTS);
        exportButtons.forEach(b -> b.setDisable(!canExport));
        permissionHint.setVisible(!canExport);
        permissionHint.setManaged(!canExport);
        if (stale || autoRefresh.isSelected()) regenerate();
    }

    // ---- preview ------------------------------------------------------------------------------

    private void regenerate() {
        stale = false;
        String text;
        try {
            text = reports.shiftHandoverReport();
        } catch (RuntimeException e) {
            info.setText("Could not generate the report: " + e.getMessage());
            return;
        }
        generatedAt = manager.now();
        if (!text.equals(preview.getText())) replaceKeepingPosition(text);
        updateInfo();
    }

    /** setText() resets the scroll position and selection; put both back so a live report can be read. */
    private void replaceKeepingPosition(String text) {
        double top = preview.getScrollTop();
        double left = preview.getScrollLeft();
        int anchor = Math.min(preview.getAnchor(), text.length());
        int caret = Math.min(preview.getCaretPosition(), text.length());
        preview.setText(text);
        preview.selectRange(anchor, caret);
        preview.setScrollTop(top);
        preview.setScrollLeft(left);
        // the skin re-measures the new text on the next layout pass, which can clamp the values above
        Platform.runLater(() -> {
            preview.setScrollTop(top);
            preview.setScrollLeft(left);
        });
    }

    private void updateInfo() {
        boolean live = autoRefresh.isSelected();
        modePill.setText(live ? "LIVE" : "SNAPSHOT");
        modePill.getStyleClass().removeAll("pill-green", "pill-grey");
        modePill.getStyleClass().add(live ? "pill-green" : "pill-grey");
        if (generatedAt == null) return;
        long lines = preview.getText().lines().count();
        info.setText((live ? "Updated " : "Generated ") + generatedAt.format(Ui.DATE_TIME) + " (hospital clock)  -  "
                + lines + " lines" + (live ? "" : "  -  Refresh Report for the latest figures"));
    }

    private void copyToClipboard() {
        ClipboardContent content = new ClipboardContent();
        content.putString(preview.getText());
        Clipboard.getSystemClipboard().setContent(content);
        ctx.showResult(OperationResult.ok("Report copied to the clipboard (" + preview.getText().lines().count() + " lines)."));
    }

    // ---- files --------------------------------------------------------------------------------

    private void saveReport() {
        Path file = chooseFile("Save shift handover report", "shift-report", ".txt", "Text files (*.txt)");
        if (file == null) return;
        OperationResult r = reports.saveShiftReport(file);
        if (r.success()) regenerate(); // keep the preview identical to what was saved
        ctx.showResult(r);
    }

    private void exportPatients() {
        Path file = chooseFile("Export patients", "patients", ".csv", "CSV files (*.csv)");
        if (file != null) ctx.showResult(reports.exportPatientsCsv(file));
    }

    private void exportActivity() {
        Path file = chooseFile("Export activity log", "activity-log", ".csv", "CSV files (*.csv)");
        if (file != null) ctx.showResult(reports.exportActivityCsv(file, ACTIVITY_EXPORT_LIMIT));
    }

    /** Save dialog with a time-stamped default name, e.g. shift-report-20261007-0130.txt; null if cancelled. */
    private Path chooseFile(String title, String baseName, String extension, String description) {
        if (!canExport()) return null;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(baseName + "-" + manager.now().format(FILE_STAMP) + extension);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(description, "*" + extension),
                new FileChooser.ExtensionFilter("All files (*.*)", "*.*"));
        File dir = initialDirectory();
        if (dir != null) chooser.setInitialDirectory(dir);
        File chosen = chooser.showSaveDialog(ctx.window());
        if (chosen == null) return null;
        lastDirectory = chosen.getParentFile();
        return chosen.toPath();
    }

    private static File initialDirectory() {
        if (lastDirectory != null && lastDirectory.isDirectory()) return lastDirectory;
        File home = new File(System.getProperty("user.home", "."));
        File documents = new File(home, "Documents");
        if (documents.isDirectory()) return documents;
        return home.isDirectory() ? home : null;
    }

    private boolean canExport() {
        if (manager.hasPermission(Permission.EXPORT_REPORTS)) return true;
        ctx.showResult(OperationResult.fail("Permission denied: your account cannot export or print reports."));
        return false;
    }

    // ---- printing -----------------------------------------------------------------------------

    private void print() {
        if (!canExport()) return;
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            ctx.showResult(OperationResult.fail("No printer available."));
            return;
        }
        if (!job.showPrintDialog(ctx.window())) {
            job.cancelJob();
            ctx.showResult(OperationResult.ok("Printing cancelled."));
            return;
        }
        regenerate(); // print up-to-date figures
        PageLayout layout = job.getJobSettings().getPageLayout();
        String footer = "Shift handover report  |  " + (generatedAt == null ? "" : generatedAt.format(FOOTER_TIME));
        List<Node> pages = printPages(preview.getText(), layout.getPrintableWidth(), layout.getPrintableHeight(), footer);
        for (int i = 0; i < pages.size(); i++) {
            if (!job.printPage(layout, pages.get(i))) {
                job.cancelJob();
                ctx.showResult(OperationResult.fail("Printing failed on page " + (i + 1) + " (" + job.getJobStatus() + ")."));
                return;
            }
        }
        if (job.endJob()) {
            ctx.showResult(OperationResult.ok("Sent " + pages.size() + (pages.size() == 1 ? " page" : " pages")
                    + " to " + job.getPrinter().getName() + "."));
        } else {
            ctx.showResult(OperationResult.fail("The printer did not accept the job (" + job.getJobStatus() + ")."));
        }
    }

    /**
     * One node per printed page: monospaced text at about 9 pt (smaller if the widest line would not fit),
     * as many lines as fit the printable height, and a footer with the page number.
     */
    static List<Node> printPages(String text, double width, double height, String footer) {
        String family = monospaceFamily();
        int widest = Math.max(1, text.lines().mapToInt(String::length).max().orElse(1));
        double size = PRINT_FONT_SIZE;
        double lineWidth = widest * charWidth(Font.font(family, size));
        if (lineWidth > width) size = Math.max(MIN_PRINT_FONT_SIZE, size * width / lineWidth);
        Font font = Font.font(family, size);
        double lineHeight = lineHeight(font);
        int linesPerPage = Math.max(1, (int) Math.floor(height / lineHeight) - 2); // two lines kept for the footer

        List<List<String>> pages = ReportService.paginate(text, linesPerPage);
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            Text body = printText(String.join("\n", pages.get(i)), font, 0);
            Line rule = new Line(0, height - lineHeight * 1.4, width, height - lineHeight * 1.4);
            rule.setStroke(Color.web("#94a3b8"));
            rule.setStrokeWidth(0.5);
            Text left = printText(footer, font, height - lineHeight);
            Text right = printText("Page " + (i + 1) + " of " + pages.size(), font, height - lineHeight);
            right.setX(Math.max(0, width - right.getLayoutBounds().getWidth()));
            nodes.add(new Group(body, rule, left, right));
        }
        return nodes;
    }

    private static Text printText(String s, Font font, double y) {
        Text t = new Text(s);
        t.setFont(font);
        t.setFill(Color.BLACK);
        t.setTextOrigin(VPos.TOP);
        t.setY(y);
        return t;
    }

    private static double charWidth(Font font) {
        Text probe = new Text("MMMMMMMMMM");
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth() / 10;
    }

    /** Distance between two baselines of a multi-line Text in {@code font}. */
    private static double lineHeight(Font font) {
        Text one = new Text("Xg");
        Text two = new Text("Xg\nXg");
        one.setFont(font);
        two.setFont(font);
        return two.getLayoutBounds().getHeight() - one.getLayoutBounds().getHeight();
    }

    /** Consolas on Windows, otherwise JavaFX's logical monospaced font. */
    private static String monospaceFamily() {
        if (monospaceFamily == null) {
            monospaceFamily = Font.getFamilies().contains("Consolas") ? "Consolas" : "Monospaced";
        }
        return monospaceFamily;
    }
}
