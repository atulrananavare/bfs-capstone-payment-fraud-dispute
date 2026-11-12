package com.example.fraudcase.application;

/** The case state does not allow the requested review operation. */
public class ReviewNotPermittedException extends RuntimeException {

    public ReviewNotPermittedException(String message) {
        super(message);
    }
}
