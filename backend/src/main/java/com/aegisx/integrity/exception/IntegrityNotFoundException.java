package com.aegisx.integrity.exception;

/** A workspace, order, payment, exception or run referenced by a request does not exist. */
public class IntegrityNotFoundException extends RuntimeException {

    public IntegrityNotFoundException(String message) {
        super(message);
    }
}
