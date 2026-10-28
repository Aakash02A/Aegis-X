package com.aegisx.integrity.exception;

/**
 * Raised when something tries to execute a financially consequential recommendation.
 *
 * <p>In this prototype financial execution is disabled by configuration default, so a human
 * approval is recorded but the side effect is refused. This is a deliberate safety property, not a
 * missing feature.</p>
 */
public class FinancialExecutionDisabledException extends RuntimeException {

    public FinancialExecutionDisabledException(String message) {
        super(message);
    }
}
