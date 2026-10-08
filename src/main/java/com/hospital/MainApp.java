package com.hospital;

import com.hospital.ui.HospitalTriageApp;
import javafx.application.Application;

/**
 * Entry point. Kept separate from the {@link Application} subclass so the app also
 * starts from an IDE or a plain classpath without extra JavaFX launcher configuration.
 */
public class MainApp {
    public static void main(String[] args) {
        Application.launch(HospitalTriageApp.class, args);
    }
}
