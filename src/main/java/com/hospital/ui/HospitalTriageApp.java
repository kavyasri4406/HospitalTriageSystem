package com.hospital.ui;

import com.hospital.model.Alert;
import com.hospital.model.AlertSeverity;
import com.hospital.model.Permission;
import com.hospital.model.StaffUser;
import com.hospital.persistence.DatabaseConfig;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.ui.views.AlertsView;
import com.hospital.ui.views.AnalyticsView;
import com.hospital.ui.views.BedGridView;
import com.hospital.ui.views.DashboardView;
import com.hospital.ui.views.DoctorsView;
import com.hospital.ui.views.LoginView;
import com.hospital.ui.views.PatientFormView;
import com.hospital.ui.views.PatientRecordsView;
import com.hospital.ui.views.QueueBoardView;
import com.hospital.ui.views.ReportsView;
import com.hospital.ui.views.SettingsView;
import com.hospital.ui.views.StaffView;
import com.hospital.ui.views.View;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JavaFX application shell: login screen, then sidebar navigation, top bar, content area,
 * status bar, toasts, keyboard shortcuts and theme (light / dark).
 * Screens are rebuilt on every login so they reflect the new user's permissions.
 */
public class HospitalTriageApp extends Application implements UiContext {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("EEE dd MMM  HH:mm:ss");
    private static final int MAX_TOASTS = 3;
    private static final KeyCode[] DIGITS = {
            KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5,
            KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9
    };

    private HospitalManager manager;
    private Stage stage;
    private Scene scene;
    private LoginView loginView;
    private StackPane shellRoot; // null while the login screen is showing
    private Timeline tickTimer;

    private final Map<Page, View> views = new EnumMap<>(Page.class);
    private final Map<Page, Button> navButtons = new EnumMap<>(Page.class);
    private Page current = Page.DASHBOARD;

    private final StackPane content = new StackPane();
    private final Label pageTitle = Ui.label("", "page-title");
    private final Label pageSubtitle = Ui.label("", "muted");
    private final Label clockLabel = Ui.label("", "clock");
    private final Label clockOffset = Ui.label("", "pill", "pill-amber");
    private final Label alertBadge = Ui.label("0", "alert-badge");
    private final Label statusMessage = Ui.label("Ready.", "status-ok");
    private final Label statusHint = Ui.label("", "muted", "small");
    private final VBox toastLayer = new VBox(10);

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        try {
            manager = HospitalManager.create(DatabaseConfig.fromEnvironment());
        } catch (RuntimeException e) {
            javafx.scene.control.Alert error = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR, e.getMessage());
            error.setHeaderText("Could not start the database");
            error.showAndWait();
            Platform.exit();
            return;
        }

        loginView = new LoginView(manager, this::onLoggedIn);
        scene = new Scene(loginView.root(), 1440, 880);
        applyTheme();
        // Dialogs and alerts open in their own windows: give them the same (light/dark) theme.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) styleWindow(w);
            }
        });
        installShortcuts();

        stage.setScene(scene);
        stage.setTitle("Hospital Patient Triage & Bed Allocator");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);

        manager.addChangeListener(this::refreshCurrent);
        manager.addAlertListener(this::onAlert);
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            showResult(OperationResult.fail("Unexpected error: " + e.getMessage()));
        });

        Timeline clockTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock()));
        clockTimer.setCycleCount(Timeline.INDEFINITE);
        clockTimer.play();

        stage.show();
        loginView.reset();
    }

    // ---- login / logout --------------------------------------------------------------

    private void onLoggedIn() {
        buildShell();
        scene.setRoot(shellRoot);
        applyTheme();
        startTickTimer();
        navigate(Page.DASHBOARD);
        manager.tick();
        StaffUser user = manager.currentUser();
        showResult(OperationResult.ok("Logged in as " + user.getFullName() + " (" + user.getRole().getDisplayName() + ")."));
    }

    private void logout() {
        if (shellRoot == null) return;
        if (tickTimer != null) tickTimer.stop();
        shellRoot = null;
        views.clear();
        toastLayer.getChildren().clear();
        manager.logout();
        scene.setRoot(loginView.root());
        loginView.reset();
    }

    /** Builds the main window for the logged-in user; screens are created fresh so they honour the user's role. */
    private void buildShell() {
        views.clear();
        navButtons.clear();
        views.put(Page.DASHBOARD, new DashboardView(this));
        views.put(Page.INTAKE, new PatientFormView(this));
        views.put(Page.QUEUE, new QueueBoardView(this));
        views.put(Page.BEDS, new BedGridView(this));
        views.put(Page.DOCTORS, new DoctorsView(this));
        views.put(Page.RECORDS, new PatientRecordsView(this));
        views.put(Page.ANALYTICS, new AnalyticsView(this));
        views.put(Page.ALERTS, new AlertsView(this));
        views.put(Page.REPORTS, new ReportsView(this));
        views.put(Page.STAFF, new StaffView(this));
        views.put(Page.SETTINGS, new SettingsView(this));

        BorderPane shell = new BorderPane();
        shell.setLeft(buildSidebar());
        shell.setTop(buildTopBar());
        shell.setCenter(content);
        shell.setBottom(buildStatusBar());

        toastLayer.getChildren().clear();
        toastLayer.setAlignment(Pos.TOP_RIGHT);
        toastLayer.setPadding(new Insets(80, 24, 0, 0));
        toastLayer.setPickOnBounds(false);
        toastLayer.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane.setAlignment(toastLayer, Pos.TOP_RIGHT);
        shellRoot = new StackPane(shell, toastLayer);
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(230);

        Label title = Ui.label("ER Triage", "brand-title");
        Label sub = Ui.label("Patient Triage & Bed Allocator", "brand-sub");
        VBox brand = new VBox(2, title, sub);
        brand.setPadding(new Insets(0, 6, 18, 6));
        sidebar.getChildren().add(brand);

        int index = 0;
        for (Page page : Page.values()) {
            Button b = new Button(page.title());
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> navigate(page));
            if (index < DIGITS.length) {
                b.setTooltip(new javafx.scene.control.Tooltip("Ctrl+" + (index + 1)));
            }
            if (page == Page.ALERTS) {
                b.setGraphic(alertBadge);
                b.setContentDisplay(ContentDisplay.RIGHT);
                b.setGraphicTextGap(10);
            }
            navButtons.put(page, b);
            sidebar.getChildren().add(b);
            index++;
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        StaffUser user = manager.currentUser();
        Label name = new Label(user.getFullName());
        name.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        name.setWrapText(true);
        Label role = Ui.label(user.getRole().getDisplayName() + "  |  " + user.getUsername(), "sidebar-footer");
        Button logout = Ui.button("Log out", "btn-secondary", this::logout);
        logout.getStyleClass().add("btn-small");
        logout.setTooltip(new javafx.scene.control.Tooltip("Ctrl+L"));
        VBox account = new VBox(3, name, role, logout);
        account.setPadding(new Insets(10, 6, 12, 6));

        Label db = Ui.label("Database: " + manager.databaseDescription(), "sidebar-footer");
        db.setWrapText(true);
        Label stack = Ui.label("JavaFX  |  Core Java  |  JDBC", "sidebar-footer");
        sidebar.getChildren().addAll(spacer, account, db, stack);
        return sidebar;
    }

    private HBox buildTopBar() {
        VBox titles = new VBox(2, pageTitle, pageSubtitle);
        Button forward = Ui.button("+15 min", "btn-secondary", () -> showResult(manager.advanceClock(15)));
        forward.getStyleClass().add("btn-small");
        forward.setDisable(!manager.hasPermission(Permission.SIMULATE));
        forward.setTooltip(new javafx.scene.control.Tooltip("Fast-forward the simulation clock to demonstrate wait-time aging"));
        HBox bar = Ui.row(14, titles, Ui.hgrow(), clockOffset, clockLabel, forward);
        bar.getStyleClass().add("topbar");
        return bar;
    }

    private HBox buildStatusBar() {
        HBox bar = Ui.row(10, statusMessage, Ui.hgrow(), statusHint);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ---- navigation and refresh ------------------------------------------------------------

    @Override
    public void navigate(Page page) {
        if (shellRoot == null) return;
        current = page;
        navButtons.forEach((p, b) -> b.getStyleClass().remove("active"));
        navButtons.get(page).getStyleClass().add("active");
        pageTitle.setText(page.title());
        pageSubtitle.setText(page.subtitle());
        content.getChildren().setAll(views.get(page).root());
        refreshCurrent();
    }

    private void refreshCurrent() {
        if (shellRoot == null || manager.currentUser() == null) return;
        View view = views.get(current);
        if (view != null) view.refresh();
        long unacked = manager.unacknowledgedAlertCount();
        alertBadge.setText(unacked > 99 ? "99+" : String.valueOf(unacked));
        alertBadge.setVisible(unacked > 0);
        updateClock();
    }

    private void updateClock() {
        if (manager == null) return;
        clockLabel.setText(manager.now().format(CLOCK));
        long offset = manager.clockOffset().toMinutes();
        clockOffset.setText("simulated +" + Ui.minutes(offset));
        clockOffset.setVisible(offset > 0);
        clockOffset.setManaged(offset > 0);
    }

    private void startTickTimer() {
        if (tickTimer != null) tickTimer.stop();
        int seconds = manager.settings().refreshSeconds();
        tickTimer = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> {
            if (shellRoot != null) manager.tick();
        }));
        tickTimer.setCycleCount(Timeline.INDEFINITE);
        tickTimer.play();
        statusHint.setText("Priority queue: custom indexed max-heap  |  auto-refresh every " + seconds + " s"
                + (manager.settings().autoAdmit() ? "  |  auto-admit ON" : ""));
    }

    // ---- keyboard shortcuts ------------------------------------------------------------------

    private void installShortcuts() {
        Page[] pages = Page.values();
        for (int i = 0; i < DIGITS.length && i < pages.length; i++) {
            Page page = pages[i];
            shortcut(new KeyCodeCombination(DIGITS[i], KeyCombination.SHORTCUT_DOWN), () -> navigate(page));
        }
        shortcut(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), () -> navigate(Page.INTAKE));
        shortcut(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> navigate(Page.RECORDS));
        shortcut(new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> showResult(manager.admitNext()));
        shortcut(new KeyCodeCombination(KeyCode.F5), () -> {
            manager.tick();
            showResult(OperationResult.ok("Refreshed."));
        });
        shortcut(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), this::logout);
    }

    /** Registers an accelerator that only works while a user is logged in. */
    private void shortcut(KeyCombination keys, Runnable action) {
        scene.getAccelerators().put(keys, () -> {
            if (shellRoot != null && manager.currentUser() != null) action.run();
        });
    }

    // ---- theme ------------------------------------------------------------------------------

    private List<String> themeSheets() {
        List<String> sheets = new ArrayList<>();
        sheets.add(Theme.stylesheet());
        if (manager.settings().darkMode()) sheets.add(Theme.darkStylesheet());
        return sheets;
    }

    private void applyTheme() {
        List<String> sheets = themeSheets();
        scene.getStylesheets().setAll(sheets);
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s != stage && s.getScene() != null) s.getScene().getStylesheets().setAll(sheets);
        }
    }

    /** Applies the theme to dialog windows (Alert, Dialog, FileChooser-less stages) as they open. */
    private void styleWindow(Window w) {
        if (!(w instanceof Stage) || w == stage || manager == null) return;
        if (w.getScene() != null) {
            w.getScene().getStylesheets().setAll(themeSheets());
        } else {
            w.sceneProperty().addListener((obs, old, s) -> {
                if (s != null) s.getStylesheets().setAll(themeSheets());
            });
        }
    }

    // ---- UiContext --------------------------------------------------------------------------

    @Override
    public HospitalManager manager() { return manager; }

    @Override
    public void settingsChanged() {
        applyTheme();
        if (shellRoot != null) startTickTimer();
    }

    @Override
    public Window window() { return stage; }

    @Override
    public void showResult(OperationResult result) {
        statusMessage.setText(result.message());
        statusMessage.getStyleClass().removeAll("status-ok", "status-error");
        statusMessage.getStyleClass().add(result.success() ? "status-ok" : "status-error");
    }

    // ---- toasts -----------------------------------------------------------------------------

    private void onAlert(Alert alert) {
        if (alert.getSeverity() == AlertSeverity.INFO || shellRoot == null) return;
        Platform.runLater(() -> showToast(alert));
    }

    private void showToast(Alert alert) {
        if (shellRoot == null) return;
        Label title = Ui.label(alert.getSeverity() + " - " + alert.getCategory().name().replace('_', ' ').toLowerCase(Locale.ROOT), "toast-title");
        Label text = Ui.label(alert.getMessage(), "toast-text");
        text.setWrapText(true);
        VBox toast = new VBox(title, text);
        toast.getStyleClass().add("toast");
        toast.setStyle("-fx-background-color: " + alert.getSeverity().getColorHex() + ";");
        toast.setMaxWidth(340);
        toast.setPrefWidth(340);
        toast.setOnMouseClicked(e -> {
            toastLayer.getChildren().remove(toast);
            navigate(Page.ALERTS);
        });

        if (toastLayer.getChildren().size() >= MAX_TOASTS) {
            toastLayer.getChildren().remove(0);
        }
        toastLayer.getChildren().add(toast);

        FadeTransition fadeOut = new FadeTransition(Duration.millis(600), toast);
        fadeOut.setToValue(0);
        SequentialTransition life = new SequentialTransition(new PauseTransition(Duration.seconds(6)), fadeOut);
        life.setOnFinished(e -> toastLayer.getChildren().remove(toast));
        life.play();
    }

    @Override
    public void stop() {
        if (manager != null) manager.close();
    }
}
