package com.hospital.ui.views;

import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.ui.Ui;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Full-window login screen shown before the main shell and again after logout.
 * Not a {@link View}: it has no UiContext because nobody is logged in yet.
 */
public class LoginView {

    /** Seeded demo accounts: username, password, role shown in the hint box. */
    private static final String[][] DEMO_ACCOUNTS = {
            {"admin", "admin123", "Administrator"},
            {"doctor", "doctor123", "Doctor"},
            {"nurse", "nurse123", "Nurse"}
    };

    private final HospitalManager manager;
    private final Runnable onLoggedIn;
    private final StackPane root = new StackPane();
    private final VBox card = new VBox();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label error = Ui.label("", "error-text");
    private final Label capsLock = Ui.pill("Caps Lock is on", "amber");
    private final Button login = Ui.button("Log in", "btn-primary", this::submit);
    private TranslateTransition shake;

    public LoginView(HospitalManager manager, Runnable onLoggedIn) {
        this.manager = manager;
        this.onLoggedIn = onLoggedIn;

        Label title = Ui.label("ER Triage", "page-title");
        title.setStyle("-fx-font-size: 24px;");
        Label subtitle = Ui.label("Patient Triage & Bed Allocator", "muted");
        HBox brand = Ui.row(12, logo(), new VBox(1, title, subtitle));

        username.setPromptText("Username");
        password.setPromptText("Password");
        username.setPrefHeight(36);
        password.setPrefHeight(36);
        username.setOnAction(e -> password.requestFocus()); // Enter moves on; Enter in the password field logs in
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);
        login.setStyle("-fx-padding: 10 14; -fx-font-size: 14px;");

        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.managedProperty().bind(error.visibleProperty());
        error.visibleProperty().bind(error.textProperty().isNotEmpty());
        capsLock.managedProperty().bind(capsLock.visibleProperty());
        capsLock.setVisible(false);
        password.addEventHandler(KeyEvent.KEY_RELEASED, e -> updateCapsLock());
        password.focusedProperty().addListener((o, was, is) -> updateCapsLock());
        // typing again hides a stale error
        username.textProperty().addListener((o, a, b) -> error.setText(""));

        VBox form = new VBox(6,
                Ui.label("Username", "form-label"), username,
                spacer(4),
                Ui.row(8, Ui.label("Password", "form-label"), Ui.hgrow(), capsLock), password);

        card.getStyleClass().add("card");
        // inline, because the .card stylesheet rule would win over setPadding/setSpacing
        card.setStyle("-fx-padding: 28 30 24 30; -fx-spacing: 14;");
        card.setMaxWidth(400);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.getChildren().addAll(brand, spacer(2),
                Ui.label("Sign in to continue", "section-title"),
                form, error, login, demoAccounts());

        Label db = Ui.label("Database: " + manager.databaseDescription(), "sidebar-footer");
        db.setWrapText(true);
        db.setMaxWidth(400);
        db.setAlignment(Pos.CENTER);
        Label stack = Ui.label("JavaFX  |  Core Java  |  JDBC", "sidebar-footer");

        VBox column = new VBox(14, card, db, stack);
        column.setAlignment(Pos.CENTER);
        column.setMaxWidth(400);
        column.setMaxHeight(Region.USE_PREF_SIZE);

        root.getChildren().add(column);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #0f2a44 0%, #143a5e 60%, #1b4a73 100%);");

        // First show: focus the username once the view is in a window.
        root.sceneProperty().addListener((o, old, scene) -> {
            if (scene != null) Platform.runLater(username::requestFocus);
        });
    }

    public Parent root() { return root; }

    /** Clears the password and any error and focuses the username field; called each time the screen is shown again. */
    public void reset() {
        password.clear();
        error.setText("");
        capsLock.setVisible(false);
        if (shake != null) shake.stop();
        card.setTranslateX(0);
        Platform.runLater(() -> {
            username.requestFocus();
            username.selectAll();
        });
    }

    private void submit() {
        OperationResult result = manager.login(username.getText(), password.getText());
        if (result.success()) {
            password.clear();
            error.setText("");
            onLoggedIn.run();
            return;
        }
        error.setText(result.message());
        if (username.getText() == null || username.getText().isBlank()) {
            username.requestFocus();
        } else {
            password.clear();
            password.requestFocus();
        }
        shakeCard();
    }

    /** Small horizontal shake so a failed attempt is noticed even when the eye is on the keyboard. */
    private void shakeCard() {
        if (shake != null) shake.stop();
        shake = new TranslateTransition(Duration.millis(50), card);
        shake.setFromX(0);
        shake.setToX(7);
        shake.setAutoReverse(true);
        shake.setCycleCount(6);
        shake.setOnFinished(e -> card.setTranslateX(0));
        shake.play();
    }

    private void updateCapsLock() {
        boolean on = password.isFocused() && Platform.isKeyLocked(KeyCode.CAPS).orElse(false);
        capsLock.setVisible(on);
    }

    /** Clickable list of the seeded accounts; clicking a row fills both fields. */
    private VBox demoAccounts() {
        VBox rows = new VBox(2);
        for (String[] account : DEMO_ACCOUNTS) {
            Label user = Ui.label(account[0], "bold");
            user.setMinWidth(70);
            Label pass = Ui.label(account[1], "muted");
            Label role = Ui.label(account[2], "muted", "small");
            role.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = Ui.row(10, user, pass, Ui.hgrow(), role);
            row.setPadding(new Insets(5, 8, 5, 8));
            row.setCursor(Cursor.HAND);
            String idle = "-fx-background-radius: 6;";
            row.setStyle(idle);
            // translucent highlight works on both the light and the dark card
            row.setOnMouseEntered(e -> row.setStyle(idle + " -fx-background-color: rgba(37,99,235,0.10);"));
            row.setOnMouseExited(e -> row.setStyle(idle));
            row.setOnMouseClicked(e -> fill(account[0], account[1]));
            Tooltip.install(row, new Tooltip("Fill in the " + account[2].toLowerCase() + " account"));
            rows.getChildren().add(row);
        }
        VBox box = new VBox(4, Ui.label("Demo accounts - click to fill in", "hint"), rows);
        box.setPadding(new Insets(10, 10, 8, 10));
        box.setStyle("-fx-border-color: rgba(100,116,139,0.35); -fx-border-radius: 8; -fx-border-style: dashed;");
        return box;
    }

    private void fill(String user, String pass) {
        username.setText(user);
        password.setText(pass);
        error.setText("");
        login.requestFocus();
    }

    /** Blue rounded square with a white medical cross, drawn with shapes (emoji do not render on Windows). */
    private static StackPane logo() {
        Rectangle vertical = new Rectangle(8, 24);
        Rectangle horizontal = new Rectangle(24, 8);
        vertical.setStyle("-fx-fill: white;");
        horizontal.setStyle("-fx-fill: white;");
        StackPane logo = new StackPane(vertical, horizontal);
        logo.setMinSize(46, 46);
        logo.setMaxSize(46, 46);
        logo.setStyle("-fx-background-color: #2563eb; -fx-background-radius: 12;");
        return logo;
    }

    private static Region spacer(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        return r;
    }
}
