package com.hospital.ui.views;

import javafx.scene.Node;

/** A screen in the main content area. */
public interface View {

    Node root();

    /** Re-reads data from the backend; called on navigation, on changes and on each timer tick. */
    void refresh();
}
