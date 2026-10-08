package com.hospital.ui;

import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import javafx.stage.Window;

/** What a screen needs from the application shell. */
public interface UiContext {

    HospitalManager manager();

    /** Shows the outcome of an action in the status bar. */
    void showResult(OperationResult result);

    void navigate(Page page);

    /** Call after settings were saved so the shell re-applies theme (dark mode) and refresh interval. */
    void settingsChanged();

    Window window();
}
