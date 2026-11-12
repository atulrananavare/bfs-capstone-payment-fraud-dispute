package com.example.fraudcase.application;

import java.util.List;

/** The review submission failed domain validation; nothing was persisted. */
public class ReviewValidationException extends RuntimeException {

    private final List<String> violations;

    public ReviewValidationException(List<String> violations) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
