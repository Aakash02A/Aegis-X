package com.aegisx.integrity.exception;

/** The request conflicts with the current state and cannot be applied as asked. */
public class IntegrityConflictException extends RuntimeException {

    public IntegrityConflictException(String message) {
        super(message);
    }
}
