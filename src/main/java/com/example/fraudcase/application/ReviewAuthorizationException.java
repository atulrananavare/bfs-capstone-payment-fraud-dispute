package com.example.fraudcase.application;

/** The resolved principal lacks reviewer authority for this operation. */
public class ReviewAuthorizationException extends RuntimeException {

    public ReviewAuthorizationException(String message) {
        super(message);
    }
}
