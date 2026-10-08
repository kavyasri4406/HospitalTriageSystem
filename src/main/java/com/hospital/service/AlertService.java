package com.hospital.service;

import com.hospital.model.Alert;
import com.hospital.model.AlertSeverity;
import com.hospital.persistence.dao.AlertDAO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Raises, stores and acknowledges alerts. The UI subscribes as a listener
 * (Observer pattern) to show pop-up notifications for new critical alerts.
 */
public class AlertService {

    private static final int MAX_IN_MEMORY = 300;

    private final AlertDAO alertDAO;
    private final HospitalClock clock;
    private final List<Alert> alerts = new ArrayList<>();          // newest first
    private final Set<String> raisedOnce = new HashSet<>();        // de-duplication keys
    private final List<Consumer<Alert>> listeners = new ArrayList<>();

    public AlertService(AlertDAO alertDAO, HospitalClock clock) {
        this.alertDAO = alertDAO;
        this.clock = clock;
    }

    public void load() {
        alerts.clear();
        alerts.addAll(alertDAO.findRecent(MAX_IN_MEMORY));
    }

    public Alert raise(AlertSeverity severity, Alert.Category category, String message, Integer patientId) {
        Alert alert = new Alert(0, severity, category, message, patientId, clock.now(), false);
        alertDAO.insert(alert);
        alerts.add(0, alert);
        if (alerts.size() > MAX_IN_MEMORY) alerts.remove(alerts.size() - 1);
        for (Consumer<Alert> listener : List.copyOf(listeners)) {
            listener.accept(alert);
        }
        return alert;
    }

    /** Raises the alert only the first time {@code key} is seen (until {@link #clearOnce} is called). */
    public void raiseOnce(String key, AlertSeverity severity, Alert.Category category, String message, Integer patientId) {
        if (raisedOnce.add(key)) {
            raise(severity, category, message, patientId);
        }
    }

    public void clearOnce(String key) {
        raisedOnce.remove(key);
    }

    public void acknowledge(int alertId) {
        for (Alert a : alerts) {
            if (a.getId() == alertId && !a.isAcknowledged()) {
                a.acknowledge();
                alertDAO.update(a);
                return;
            }
        }
    }

    public void acknowledgeAll() {
        alerts.forEach(Alert::acknowledge);
        alertDAO.acknowledgeAll();
    }

    public List<Alert> recent() {
        return Collections.unmodifiableList(alerts);
    }

    public long unacknowledgedCount() {
        return alerts.stream().filter(a -> !a.isAcknowledged()).count();
    }

    public void addListener(Consumer<Alert> listener) {
        listeners.add(listener);
    }

    public void clear() {
        alerts.clear();
        raisedOnce.clear();
    }
}
