package com.hospital.ui.views;

import com.hospital.model.StaffUser;
import com.hospital.service.AuthService;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.event.ActionEvent;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Arrays;

/**
 * Modal dialog in which the logged-in user changes their own password.
 * Also holds the small password-field helpers (strength meter, match indicator) that
 * {@link StaffView} reuses for its create-account form and reset-password dialog.
 */
public final class ChangePasswordDialog {

    private ChangePasswordDialog() {}

    public static void show(UiContext ctx) {
        HospitalManager manager = ctx.manager();
        StaffUser user = manager.currentUser();
        if (user == null) {
            ctx.showResult(OperationResult.fail("Please log in first."));
            return;
        }

        PasswordField current = passwordField("Your current password");
        PasswordField next = passwordField("At least " + AuthService.MIN_PASSWORD_LENGTH + " characters");
        PasswordField confirm = passwordField("Type the new password again");
        Label error = errorLabel();

        Label strength = strengthMeter(next);
        Label match = matchIndicator(next, confirm);
        clearOnEdit(error, current, next, confirm);

        GridPane grid = formGrid(150);
        addField(grid, 0, "Current password", current);
        addField(grid, 1, "New password", withIndicators(next, strength));
        addField(grid, 2, "Confirm new password", withIndicators(confirm, match));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(ctx.window());
        dialog.setTitle("Change password");
        dialog.setHeaderText("Change the password for " + user.getFullName() + " (" + user.getUsername() + ")");
        ButtonType change = new ButtonType("Change Password", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(change, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(12, grid, error));
        dialog.getDialogPane().setPrefWidth(500);
        styleCancel(dialog);
        fitWindowOnChange(dialog, strength.visibleProperty(), match.visibleProperty(), error.textProperty());

        Button ok = (Button) dialog.getDialogPane().lookupButton(change);
        ok.getStyleClass().add("btn-primary");
        ok.disableProperty().bind(anyEmpty(current, next, confirm));

        OperationResult[] outcome = new OperationResult[1];
        // The filter runs before the dialog closes, so consuming the event keeps it open.
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            String problem = mismatch(next, confirm);
            if (problem == null && next.getText().equals(current.getText())) {
                problem = "The new password must be different from the current one.";
            }
            if (problem != null) {
                error.setText(problem);
                e.consume();
                return;
            }
            OperationResult result = manager.changeOwnPassword(current.getText(), next.getText());
            if (!result.success()) {
                error.setText(result.message());
                e.consume();
                return;
            }
            outcome[0] = result;
        });
        dialog.setOnShown(e -> Platform.runLater(current::requestFocus));
        dialog.showAndWait();
        if (outcome[0] != null) ctx.showResult(outcome[0]);
    }

    // ---- helpers shared with StaffView ------------------------------------------------------

    static PasswordField passwordField(String prompt) {
        PasswordField field = new PasswordField();
        field.setPromptText(prompt);
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    /** Red message label that takes no space while empty. */
    static Label errorLabel() {
        Label error = Ui.label("", "error-text");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.managedProperty().bind(error.visibleProperty());
        error.visibleProperty().bind(error.textProperty().isNotEmpty());
        return error;
    }

    /**
     * A dialog window keeps the size it opened with; this re-fits it whenever one of the triggers
     * changes (an indicator or error appearing), so the button bar is never pushed out of view.
     */
    static void fitWindowOnChange(Dialog<?> dialog, Observable... triggers) {
        for (Observable trigger : triggers) {
            trigger.addListener(o -> Platform.runLater(() -> {
                if (dialog.getDialogPane().getScene() == null) return;
                Window window = dialog.getDialogPane().getScene().getWindow();
                if (window != null && window.isShowing()) window.sizeToScene();
            }));
        }
    }

    /** Hides a stale error message as soon as the user edits one of the fields again. */
    static void clearOnEdit(Label error, TextField... fields) {
        for (TextField field : fields) field.textProperty().addListener((o, a, b) -> error.setText(""));
    }

    /** Gives the dialog's Cancel button the app's secondary style instead of the plain default look. */
    static void styleCancel(Dialog<?> dialog) {
        Node cancel = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancel != null) cancel.getStyleClass().add("btn-secondary");
    }

    /**
     * Adds a "label | field" row. Both are baseline-aligned, so the label lines up with the
     * field's text even when the field carries indicators underneath (see {@link #withIndicators}).
     */
    static void addField(GridPane grid, int row, String label, Node field) {
        Label name = Ui.label(label, "form-label");
        GridPane.setValignment(name, VPos.BASELINE);
        GridPane.setValignment(field, VPos.BASELINE);
        grid.addRow(row, name, field);
    }

    /**
     * Stacks a field above its indicator pills or hints. Keeping them in the field's own cell
     * (rather than in a grid row of their own) means a hidden indicator leaves no empty gap.
     */
    static VBox withIndicators(Node field, Node... indicators) {
        VBox box = new VBox(4, field);
        box.getChildren().addAll(indicators);
        return box;
    }

    /** Wrapping hint for a dialog; the fixed preferred width lets the dialog reserve its wrapped height. */
    static Label dialogHint(String text, double width) {
        Label hint = Ui.label(text, "hint");
        hint.setWrapText(true);
        hint.setPrefWidth(width);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        return hint;
    }

    /** Two-column form grid: fixed label column, growing field column. */
    static GridPane formGrid(double labelWidth) {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints(labelWidth);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    /** True while any of the fields is empty (used to disable OK buttons). */
    static BooleanBinding anyEmpty(TextField... fields) {
        Observable[] deps = Arrays.stream(fields).map(TextField::textProperty).toArray(Observable[]::new);
        return Bindings.createBooleanBinding(
                () -> Arrays.stream(fields).anyMatch(f -> f.getText() == null || f.getText().isEmpty()), deps);
    }

    /** Error text when the two password fields differ, otherwise null. */
    static String mismatch(PasswordField password, PasswordField confirm) {
        return password.getText().equals(confirm.getText()) ? null : "The passwords do not match.";
    }

    /** Pill that rates the password as it is typed; hidden while the field is empty. */
    static Label strengthMeter(PasswordField field) {
        Label pill = Ui.pill("", "grey");
        pill.managedProperty().bind(pill.visibleProperty());
        Runnable update = () -> {
            String text = field.getText() == null ? "" : field.getText();
            pill.setVisible(!text.isEmpty());
            String[] rating = rate(text);
            pill.setText("Strength: " + rating[0]);
            pill.getStyleClass().removeAll("pill-grey", "pill-red", "pill-amber", "pill-green");
            pill.getStyleClass().add("pill-" + rating[1]);
        };
        field.textProperty().addListener((o, a, b) -> update.run());
        update.run();
        return pill;
    }

    /** Live "match / do not match" pill for a confirmation field; hidden while it is empty. */
    static Label matchIndicator(PasswordField password, PasswordField confirm) {
        Label pill = Ui.pill("", "grey");
        pill.managedProperty().bind(pill.visibleProperty());
        Runnable update = () -> {
            boolean empty = confirm.getText() == null || confirm.getText().isEmpty();
            pill.setVisible(!empty);
            boolean same = mismatch(password, confirm) == null;
            pill.setText(same ? "Passwords match" : "Passwords do not match");
            pill.getStyleClass().removeAll("pill-grey", "pill-red", "pill-green");
            pill.getStyleClass().add(same ? "pill-green" : "pill-red");
        };
        password.textProperty().addListener((o, a, b) -> update.run());
        confirm.textProperty().addListener((o, a, b) -> update.run());
        update.run();
        return pill;
    }

    /**
     * Rough strength rating: one point each for length >= 10, length >= 14, mixed case,
     * a digit and a symbol. Returns {label, pill colour}.
     */
    static String[] rate(String password) {
        if (password.length() < AuthService.MIN_PASSWORD_LENGTH) return new String[]{"too short", "red"};
        int score = 0;
        if (password.length() >= 10) score++;
        if (password.length() >= 14) score++;
        if (password.chars().anyMatch(Character::isLowerCase) && password.chars().anyMatch(Character::isUpperCase)) score++;
        if (password.chars().anyMatch(Character::isDigit)) score++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) score++;
        if (score <= 1) return new String[]{"weak", "red"};
        if (score <= 2) return new String[]{"fair", "amber"};
        return new String[]{"strong", "green"};
    }
}
