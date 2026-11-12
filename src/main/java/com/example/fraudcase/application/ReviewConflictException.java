package com.example.fraudcase.application;

/**
 * A concurrent or duplicate review submission lost the race — another
 * disposition for this recommendation won, or the case changed mid-review.
 */
public class ReviewConflictException extends RuntimeException {

    public ReviewConflictException(String message) {
        super(message);
    }
}
