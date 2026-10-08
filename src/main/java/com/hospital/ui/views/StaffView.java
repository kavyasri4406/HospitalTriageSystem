package com.hospital.ui.views;

import com.hospital.model.Permission;
import com.hospital.model.Role;
import com.hospital.model.StaffUser;
import com.hospital.service.AuthService;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.ui.Ui;
import com.hospital.ui.UiContext;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Staff & Access screen: the user's own account, staff account administration
 * (administrators only) and the role-permission matrix that documents access control.
 */
public class StaffView implements View {

    /** Characters for generated temporary passwords (no look-alikes such as 0/O or 1/l). */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final double ROW_HEIGHT = 30;
    private static final double HEADER_HEIGHT = 30;

    private final UiContext ctx;
    private final HospitalManager manager;
    private final HBox columns = new HBox(18);
    private final ScrollPane root;
    private Boolean builtForAdmin; // layout depends on MANAGE_STAFF; rebuilt if the user's role changes

    // ---- my account
    private final Label avatarText = new Label();
    private final Label myName = Ui.label("", "section-title");
    private final Label myUsername = Ui.label("", "muted");
    private final Label myRole = Ui.pill("", "blue");
    private final Label myLastLogin = new Label();
    private final Label myCreated = new Label();
    private final Label myPermissions = new Label();
    private final VBox accountCard;

    // ---- staff table and actions on the selected row
    private final ObservableList<StaffUser> staff = FXCollections.observableArrayList();
    private final FilteredList<StaffUser> filtered = new FilteredList<>(staff);
    private final TableView<StaffUser> table = new TableView<>();
    private final TextField filter = new TextField();
    private final Label summary = Ui.label("", "muted");
    private final Label selectedLabel = Ui.label("", "bold");
    private final Button toggleActive = Ui.button("Deactivate", "btn-danger", this::toggleActive);
    private final ComboBox<Role> roleBox = new ComboBox<>();
    private final Button applyRole = Ui.button("Apply Role", "btn-secondary", this::applyRole);
    private final Button resetPassword = Ui.button("Reset Password", "btn-warning", this::resetPassword);
    private final VBox staffCard;
    private String staffSignature = "";
    private int shownId = -1;         // account whose role is loaded into roleBox
    private boolean restoring;        // true while refresh() swaps the table items

    // ---- add staff form (kept across refreshes)
    private final TextField newUsername = new TextField();
    private final TextField newFullName = new TextField();
    private final ComboBox<Role> newRole = new ComboBox<>();
    private final PasswordField newPassword = ChangePasswordDialog.passwordField(
            "At least " + AuthService.MIN_PASSWORD_LENGTH + " characters");
    private final PasswordField newConfirm = ChangePasswordDialog.passwordField("Type the password again");
    private final VBox formErrors = new VBox(3);
    private final VBox addCard;

    // ---- permission matrix
    private final GridPane matrix = new GridPane();
    private final VBox permissionsCard;
    private Role matrixRole; // role highlighted as "your role"

    public StaffView(UiContext ctx) {
        this.ctx = ctx;
        this.manager = ctx.manager();
        accountCard = buildAccountCard();
        staffCard = buildStaffCard();
        addCard = buildAddCard();
        permissionsCard = buildPermissionsCard();

        VBox content = new VBox(columns);
        content.setPadding(new Insets(20, 22, 22, 22));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        layout(manager.hasPermission(Permission.MANAGE_STAFF));
    }

    /** Arranges the cards for an administrator (full management) or another role (read-only). */
    private void layout(boolean admin) {
        builtForAdmin = admin;
        VBox left = new VBox(16, accountCard);
        VBox right = new VBox(16);
        if (admin) {
            left.getChildren().add(addCard);
            right.getChildren().addAll(staffCard, permissionsCard);
        } else {
            Label note = Ui.label("Only administrators can create, deactivate or change staff accounts and reset "
                    + "passwords. Ask an administrator if your role or access needs to change.", "muted");
            note.setWrapText(true);
            note.setMaxWidth(Double.MAX_VALUE);
            right.getChildren().addAll(Ui.card("Staff accounts", note), permissionsCard);
        }
        left.setPrefWidth(370);
        left.setMinWidth(330);
        right.setMinWidth(0);
        HBox.setHgrow(right, Priority.ALWAYS);
        columns.getChildren().setAll(left, right);
    }

    @Override
    public Node root() { return root; }

    @Override
    public void refresh() {
        boolean admin = manager.hasPermission(Permission.MANAGE_STAFF);
        if (builtForAdmin == null || admin != builtForAdmin) layout(admin);
        showAccount();
        if (admin) reloadStaff();
    }

    // =====================================================================
    // My account
    // =====================================================================

    private VBox buildAccountCard() {
        Circle circle = new Circle(24);
        circle.setStyle("-fx-fill: #2563eb;");
        avatarText.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");
        StackPane avatar = new StackPane(circle, avatarText);
        avatar.setMinSize(48, 48);
        myName.setWrapText(true);
        HBox header = Ui.row(12, avatar, new VBox(2, myName, Ui.row(8, myUsername, myRole)));

        GridPane details = ChangePasswordDialog.formGrid(110);
        int r = 0;
        details.addRow(r++, Ui.label("Last login", "form-label"), myLastLogin);
        details.addRow(r++, Ui.label("Member since", "form-label"), myCreated);
        details.addRow(r++, Ui.label("Permissions", "form-label"), myPermissions);
        details.addRow(r, Ui.label("Status", "form-label"), Ui.row(0, Ui.pill("Active", "green")));

        Button change = Ui.button("Change My Password", "btn-primary", () -> ChangePasswordDialog.show(ctx));
        Label hint = Ui.label("Use at least " + AuthService.MIN_PASSWORD_LENGTH
                + " characters; mixing case, digits and symbols makes it stronger.", "hint");
        hint.setWrapText(true);
        return Ui.card("My account", header, details, change, hint);
    }

    private void showAccount() {
        StaffUser me = manager.currentUser();
        if (me == null) return;
        avatarText.setText(initials(me.getFullName()));
        myName.setText(me.getFullName());
        myUsername.setText(me.getUsername());
        myRole.setText(me.getRole().getDisplayName());
        myLastLogin.setText(format(me.getLastLogin(), "Never"));
        myCreated.setText(format(me.getCreatedAt(), "-"));
        myPermissions.setText(me.getRole().getPermissions().size() + " of " + Permission.values().length + " actions");
        if (me.getRole() != matrixRole) fillMatrix(me.getRole());
    }

    // =====================================================================
    // Staff table (administrators)
    // =====================================================================

    private VBox buildStaffCard() {
        TableColumn<StaffUser, String> username = text("Username", 120, u -> u.getUsername() + (isMe(u) ? "  (you)" : ""));
        TableColumn<StaffUser, String> fullName = text("Full name", 170, StaffUser::getFullName);
        TableColumn<StaffUser, String> role = text("Role", 110, u -> u.getRole().getDisplayName());

        TableColumn<StaffUser, Boolean> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().isActive()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean active, boolean empty) {
                super.updateItem(active, empty);
                setText(null);
                setGraphic(empty || active == null ? null
                        : active ? Ui.pill("Active", "green") : Ui.pill("Deactivated", "grey"));
            }
        });
        status.setPrefWidth(100);

        table.getColumns().add(username);
        table.getColumns().add(fullName);
        table.getColumns().add(role);
        table.getColumns().add(status);
        table.getColumns().add(dateColumn("Created", StaffUser::getCreatedAt, "-"));
        table.getColumns().add(dateColumn("Last login", StaffUser::getLastLogin, "Never"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.label("No accounts match the filter.", "muted"));
        table.setFixedCellSize(ROW_HEIGHT);
        fitTableHeight(4);

        SortedList<StaffUser> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!restoring) onSelected(b);
        });

        filter.setPromptText("Filter by name, username or role");
        filter.setPrefWidth(260);
        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(q.isEmpty() ? null : u -> u.getUsername().contains(q)
                    || u.getFullName().toLowerCase(Locale.ROOT).contains(q)
                    || u.getRole().getDisplayName().toLowerCase(Locale.ROOT).contains(q));
        });
        summary.setWrapText(true);
        HBox toolbar = Ui.row(10, filter, Ui.hgrow());

        roleBox.getItems().addAll(Role.values());
        roleBox.setPromptText("Role");
        toggleActive.setMinWidth(Region.USE_PREF_SIZE);
        applyRole.setMinWidth(Region.USE_PREF_SIZE);
        resetPassword.setMinWidth(Region.USE_PREF_SIZE);
        selectedLabel.setMinWidth(Region.USE_PREF_SIZE);
        FlowPane actions = new FlowPane(10, 8,
                selectedLabel, toggleActive, Ui.row(6, roleBox, applyRole), resetPassword);
        actions.setAlignment(Pos.CENTER_LEFT);
        onSelected(null);

        return Ui.card("Staff accounts", summary, toolbar, table, actions);
    }

    /** Re-reads the accounts; the table is only touched when something changed, so selection and scroll stay put. */
    private void reloadStaff() {
        List<StaffUser> list = manager.listStaff();
        String signature = signature(list);
        if (signature.equals(staffSignature)) return;
        staffSignature = signature;

        StaffUser selected = table.getSelectionModel().getSelectedItem();
        int keepId = selected == null ? -1 : selected.getId();
        restoring = true;
        try {
            staff.setAll(list);
            selectById(keepId);
        } finally {
            restoring = false;
        }
        onSelected(table.getSelectionModel().getSelectedItem());
        fitTableHeight(list.size());

        long active = list.stream().filter(StaffUser::isActive).count();
        Map<Role, Integer> byRole = new EnumMap<>(Role.class);
        for (StaffUser u : list) byRole.merge(u.getRole(), 1, Integer::sum);
        StringBuilder text = new StringBuilder(list.size() + " accounts  |  " + active + " active");
        for (Role role : Role.values()) {
            text.append("  |  ").append(byRole.getOrDefault(role, 0)).append(' ').append(role.getDisplayName());
        }
        summary.setText(text.toString());
    }

    /** Sizes the table to its rows (4 to 12 visible) so a small staff list leaves no block of empty rows. */
    private void fitTableHeight(int rows) {
        int visible = Math.max(4, Math.min(12, rows));
        table.setPrefHeight(HEADER_HEIGHT + visible * ROW_HEIGHT + 2);
        table.setMinHeight(table.getPrefHeight());
    }

    private static String signature(List<StaffUser> list) {
        StringBuilder sb = new StringBuilder();
        for (StaffUser u : list) {
            sb.append(u.getId()).append('|').append(u.getUsername()).append('|').append(u.getFullName()).append('|')
                    .append(u.getRole()).append('|').append(u.isActive()).append('|').append(u.getLastLogin()).append(';');
        }
        return sb.toString();
    }

    private void selectById(int id) {
        for (StaffUser u : table.getItems()) {
            if (u.getId() == id) {
                table.getSelectionModel().select(u);
                return;
            }
        }
        table.getSelectionModel().clearSelection();
    }

    /** Updates the action bar; the role picker is only reset when a different account is selected. */
    private void onSelected(StaffUser u) {
        int id = u == null ? -1 : u.getId();
        if (id != shownId) {
            shownId = id;
            roleBox.setValue(u == null ? null : u.getRole());
        }
        boolean none = u == null;
        selectedLabel.setText(none ? "Select an account to manage it." : "Selected: " + u.getUsername());
        selectedLabel.getStyleClass().setAll("label", none ? "muted" : "bold");
        boolean activate = !none && !u.isActive();
        toggleActive.setText(activate ? "Activate" : "Deactivate");
        toggleActive.getStyleClass().removeAll("btn-danger", "btn-success");
        toggleActive.getStyleClass().add(activate ? "btn-success" : "btn-danger");
        boolean self = !none && isMe(u);
        toggleActive.setDisable(none || (self && u.isActive()));
        toggleActive.setTooltip(self ? new Tooltip("You cannot deactivate your own account") : null);
        roleBox.setDisable(none);
        applyRole.setDisable(none);
        resetPassword.setDisable(none);
    }

    private StaffUser selectedOrWarn() {
        StaffUser u = table.getSelectionModel().getSelectedItem();
        if (u == null) ctx.showResult(OperationResult.fail("Select an account in the table first."));
        return u;
    }

    private void toggleActive() {
        StaffUser u = selectedOrWarn();
        if (u == null) return;
        boolean activate = !u.isActive();
        String message = activate
                ? u.getFullName() + " (" + u.getUsername() + ") will be able to log in again."
                : u.getFullName() + " (" + u.getUsername() + ") will no longer be able to log in. "
                + "Their audit history is kept and the account can be activated again later.";
        if (!Ui.confirm(ctx.window(), (activate ? "Activate" : "Deactivate") + " account", message)) return;
        report(manager.setStaffActive(u.getId(), activate));
    }

    private void applyRole() {
        StaffUser u = selectedOrWarn();
        if (u == null) return;
        Role role = roleBox.getValue();
        if (role == null || role == u.getRole()) {
            ctx.showResult(OperationResult.fail("Pick a different role for '" + u.getUsername() + "' first."));
            return;
        }
        if (isMe(u) && !role.can(Permission.MANAGE_STAFF) && !Ui.confirm(ctx.window(), "Change your own role",
                "You will lose access to staff management and other administrator tools as soon as you become a "
                        + role.getDisplayName().toLowerCase(Locale.ROOT) + ". Continue?")) {
            return;
        }
        report(manager.changeStaffRole(u.getId(), role));
    }

    /** Dialog with new + confirm password (and a generator for a temporary one) for the selected account. */
    private void resetPassword() {
        StaffUser u = selectedOrWarn();
        if (u == null) return;
        PasswordField password = ChangePasswordDialog.passwordField(
                "At least " + AuthService.MIN_PASSWORD_LENGTH + " characters");
        PasswordField confirm = ChangePasswordDialog.passwordField("Type the new password again");
        TextField generated = new TextField();
        generated.setEditable(false);
        generated.managedProperty().bind(generated.visibleProperty());
        generated.setVisible(false);
        Label error = ChangePasswordDialog.errorLabel();
        Button generate = Ui.button("Generate temporary password", "btn-secondary", () -> {
            String temp = temporaryPassword();
            password.setText(temp);
            confirm.setText(temp);
            generated.setText(temp);
            generated.setVisible(true);
        });
        generate.getStyleClass().add("btn-small");
        // typing a password by hand hides a stale generated one
        password.textProperty().addListener((o, a, b) -> {
            if (!b.equals(generated.getText())) generated.setVisible(false);
        });

        Label strength = ChangePasswordDialog.strengthMeter(password);
        Label match = ChangePasswordDialog.matchIndicator(password, confirm);
        ChangePasswordDialog.clearOnEdit(error, password, confirm);

        GridPane grid = ChangePasswordDialog.formGrid(150);
        ChangePasswordDialog.addField(grid, 0, "New password", ChangePasswordDialog.withIndicators(password, strength));
        ChangePasswordDialog.addField(grid, 1, "Confirm password", ChangePasswordDialog.withIndicators(confirm, match));
        grid.add(new VBox(6, generate, generated), 1, 2);
        Label hint = ChangePasswordDialog.dialogHint("Give the new password to " + u.getFullName()
                + " in person. They can change it afterwards with Change My Password on the Staff & Access screen.", 470);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(ctx.window());
        dialog.setTitle("Reset password");
        dialog.setHeaderText("Set a new password for " + u.getFullName() + " (" + u.getUsername() + ")");
        ButtonType reset = new ButtonType("Reset Password", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(reset, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(12, grid, hint, error));
        dialog.getDialogPane().setPrefWidth(520);
        ChangePasswordDialog.styleCancel(dialog);
        ChangePasswordDialog.fitWindowOnChange(dialog, strength.visibleProperty(), match.visibleProperty(),
                generated.visibleProperty(), error.textProperty());

        Button ok = (Button) dialog.getDialogPane().lookupButton(reset);
        ok.getStyleClass().add("btn-warning");
        ok.disableProperty().bind(ChangePasswordDialog.anyEmpty(password, confirm));
        OperationResult[] outcome = new OperationResult[1];
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            String problem = ChangePasswordDialog.mismatch(password, confirm);
            if (problem != null) {
                error.setText(problem);
                e.consume();
                return;
            }
            OperationResult result = manager.resetStaffPassword(u.getId(), password.getText());
            if (!result.success()) {
                error.setText(result.message());
                e.consume();
                return;
            }
            outcome[0] = result;
        });
        dialog.setOnShown(e -> Platform.runLater(password::requestFocus));
        dialog.showAndWait();
        if (outcome[0] != null) ctx.showResult(outcome[0]);
    }

    private void report(OperationResult result) {
        ctx.showResult(result);
        if (result.success()) refresh();
    }

    // =====================================================================
    // Add staff member (administrators)
    // =====================================================================

    private VBox buildAddCard() {
        newUsername.setPromptText("e.g. j.smith");
        newFullName.setPromptText("e.g. Dr. Jane Smith");
        newRole.getItems().addAll(Role.values());
        newRole.setValue(Role.NURSE);
        newRole.setMaxWidth(Double.MAX_VALUE);

        // fixed preferred width: the baseline-aligned grid row sizes itself from the label's preferred
        // width, so a free-width wrapping label would get a single line and be cut off
        Label usernameHint = ChangePasswordDialog.dialogHint(
                "3-40 characters: lowercase letters, digits, '.', '_' or '-'", 170);

        GridPane grid = ChangePasswordDialog.formGrid(110);
        int r = 0;
        ChangePasswordDialog.addField(grid, r++, "Username", ChangePasswordDialog.withIndicators(newUsername, usernameHint));
        ChangePasswordDialog.addField(grid, r++, "Full name", newFullName);
        ChangePasswordDialog.addField(grid, r++, "Role", newRole);
        ChangePasswordDialog.addField(grid, r++, "Password", ChangePasswordDialog.withIndicators(newPassword,
                ChangePasswordDialog.strengthMeter(newPassword)));
        ChangePasswordDialog.addField(grid, r, "Confirm", ChangePasswordDialog.withIndicators(newConfirm,
                ChangePasswordDialog.matchIndicator(newPassword, newConfirm)));

        Button create = Ui.button("Create Account", "btn-primary", this::createStaff);
        HBox buttons = Ui.row(10, create, Ui.button("Clear", "btn-secondary", this::clearForm));
        return Ui.card("Add staff member", grid, formErrors, buttons);
    }

    private void createStaff() {
        formErrors.getChildren().clear();
        String mismatch = ChangePasswordDialog.mismatch(newPassword, newConfirm);
        if (mismatch != null) {
            showErrors(List.of(mismatch));
            ctx.showResult(OperationResult.fail(mismatch));
            return;
        }
        OperationResult result = manager.createStaff(newUsername.getText(), newFullName.getText(),
                newRole.getValue(), newPassword.getText());
        ctx.showResult(result);
        if (!result.success()) {
            showErrors(result.errors().isEmpty() ? List.of(result.message()) : result.errors());
            return;
        }
        String created = newUsername.getText().trim().toLowerCase(Locale.ROOT);
        clearForm();
        refresh();
        for (StaffUser u : table.getItems()) {
            if (u.getUsername().equals(created)) {
                table.getSelectionModel().select(u);
                table.scrollTo(u);
                break;
            }
        }
    }

    private void showErrors(List<String> errors) {
        formErrors.getChildren().clear();
        for (String e : errors) {
            Label l = Ui.label("-  " + e, "error-text");
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            formErrors.getChildren().add(l);
        }
    }

    private void clearForm() {
        newUsername.clear();
        newFullName.clear();
        newRole.setValue(Role.NURSE);
        newPassword.clear();
        newConfirm.clear();
        formErrors.getChildren().clear();
    }

    // =====================================================================
    // Role permission matrix (everyone)
    // =====================================================================

    private VBox buildPermissionsCard() {
        matrix.setHgap(0);
        matrix.setVgap(0);
        ColumnConstraints description = new ColumnConstraints();
        description.setHgrow(Priority.ALWAYS);
        description.setMinWidth(170);
        matrix.getColumnConstraints().add(description);
        for (int i = 0; i < Role.values().length; i++) {
            ColumnConstraints col = new ColumnConstraints(125);
            col.setHalignment(HPos.CENTER);
            matrix.getColumnConstraints().add(col);
        }
        Label intro = Ui.label("Every logged-in user can view all screens; these actions are restricted by role "
                + "and are checked again by the service layer, not just hidden in the interface.", "muted");
        intro.setWrapText(true);
        Label security = Ui.label("Passwords are stored only as salted PBKDF2-HMAC-SHA256 hashes. After "
                + AuthService.MAX_FAILED_ATTEMPTS + " failed logins an account is locked for "
                + AuthService.LOCKOUT.toMinutes() + " minute(s). Deactivated accounts cannot log in.", "hint");
        security.setWrapText(true);
        return Ui.card("Role permissions", intro, matrix, security);
    }

    /** Rebuilds the matrix with the given role's column highlighted. */
    private void fillMatrix(Role mine) {
        matrixRole = mine;
        matrix.getChildren().clear();
        Role[] roles = Role.values();
        Permission[] permissions = Permission.values();
        int lastRow = permissions.length + 1;

        // backgrounds first so they sit behind the cells: own-role column, then zebra stripes
        for (int c = 0; c < roles.length; c++) {
            if (roles[c] != mine) continue;
            Region highlight = background("rgba(37,99,235,0.08)");
            matrix.add(highlight, c + 1, 0, 1, lastRow + 1);
        }
        for (int i = 0; i < permissions.length; i += 2) {
            matrix.add(background("rgba(100,116,139,0.07)"), 0, i + 1, roles.length + 1, 1);
        }

        matrix.add(cell(Ui.label("Action", "form-label"), HPos.LEFT), 0, 0);
        for (int c = 0; c < roles.length; c++) {
            VBox header = new VBox(1, Ui.label(roles[c].getDisplayName(), "form-label"));
            header.setAlignment(Pos.CENTER);
            if (roles[c] == mine) header.getChildren().add(Ui.label("your role", "hint"));
            matrix.add(cell(header, HPos.CENTER), c + 1, 0);
        }
        for (int i = 0; i < permissions.length; i++) {
            Label name = Ui.label(permissions[i].getDescription());
            matrix.add(cell(name, HPos.LEFT), 0, i + 1);
            for (int c = 0; c < roles.length; c++) {
                Node mark = roles[c].can(permissions[i]) ? Ui.pill("Yes", "green") : Ui.label("-", "muted");
                matrix.add(cell(mark, HPos.CENTER), c + 1, i + 1);
            }
        }
        matrix.add(cell(Ui.label("Total", "bold"), HPos.LEFT), 0, lastRow);
        for (int c = 0; c < roles.length; c++) {
            Label total = Ui.label(roles[c].getPermissions().size() + " / " + permissions.length, "bold");
            matrix.add(cell(total, HPos.CENTER), c + 1, lastRow);
        }
    }

    private static Node cell(Node content, HPos align) {
        StackPane cell = new StackPane(content);
        cell.setAlignment(align == HPos.LEFT ? Pos.CENTER_LEFT : Pos.CENTER);
        cell.setPadding(new Insets(6, 10, 6, 10));
        GridPane.setHalignment(cell, align);
        GridPane.setValignment(cell, VPos.CENTER);
        return cell;
    }

    /** Translucent fill so it reads on both light and dark cards. */
    private static Region background(String color) {
        Region r = new Region();
        r.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 6;");
        r.setMouseTransparent(true);
        return r;
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private boolean isMe(StaffUser u) {
        StaffUser me = manager.currentUser();
        return me != null && u != null && me.getId() == u.getId();
    }

    private static TableColumn<StaffUser, String> text(String title, double width, Function<StaffUser, String> getter) {
        TableColumn<StaffUser, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(getter.apply(c.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    /** Date column that sorts chronologically but displays "dd MMM HH:mm". */
    private static TableColumn<StaffUser, LocalDateTime> dateColumn(String title, Function<StaffUser, LocalDateTime> getter,
                                                                   String none) {
        TableColumn<StaffUser, LocalDateTime> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(getter.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDateTime time, boolean empty) {
                super.updateItem(time, empty);
                setText(empty ? null : format(time, none));
            }
        });
        col.setPrefWidth(110);
        return col;
    }

    private static String format(LocalDateTime time, String none) {
        return time == null ? none : time.format(Ui.DATE_TIME);
    }

    private static String initials(String fullName) {
        StringBuilder sb = new StringBuilder();
        for (String part : fullName.trim().split("\\s+")) {
            if (part.isEmpty() || part.endsWith(".")) continue; // skip titles such as "Dr."
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (sb.length() == 2) break;
        }
        return sb.isEmpty() ? "?" : sb.toString();
    }

    /** Random temporary password in three groups of four, e.g. "Hk7p-Rt3m-Q9wa". */
    private static String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
