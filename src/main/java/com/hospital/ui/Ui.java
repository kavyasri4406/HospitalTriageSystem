package com.hospital.ui;

import com.hospital.model.TriageLevel;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.format.DateTimeFormatter;

/** Small factory helpers shared by all screens. */
public final class Ui {

    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private Ui() {}

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    public static Button button(String text, String styleClass, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(styleClass);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static VBox card(String title, Node... content) {
        VBox box = new VBox();
        box.getStyleClass().add("card");
        if (title != null) box.getChildren().add(label(title, "card-title"));
        box.getChildren().addAll(content);
        return box;
    }

    public static Label esiBadge(TriageLevel level) {
        Label badge = new Label("ESI " + level.getEsi());
        badge.getStyleClass().add("esi-badge");
        badge.setStyle(esiStyle(level));
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    public static String esiStyle(TriageLevel level) {
        String text = level == TriageLevel.URGENT ? "#1f2937" : "white";
        return "-fx-background-color: " + level.getColorHex() + "; -fx-text-fill: " + text + ";";
    }

    public static Label pill(String text, String color) {
        Label l = new Label(text);
        l.getStyleClass().addAll("pill", "pill-" + color);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    public static Region hgrow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double spacing, Node... nodes) {
        HBox box = new HBox(spacing, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static ProgressBar ratioBar(double ratio) {
        ProgressBar bar = new ProgressBar(ratio);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(12);
        bar.getStyleClass().add(ratio >= 0.9 ? "bar-red" : ratio >= 0.7 ? "bar-amber" : "bar-green");
        return bar;
    }

    public static String minutes(long m) {
        if (m < 60) return m + " min";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }
}
