package com.hospital.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ValidationResult {

    private final List<String> errors = new ArrayList<>();

    void addError(String error) {
        errors.add(error);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    @Override
    public String toString() {
        return isValid() ? "valid" : String.join("; ", errors);
    }
}
