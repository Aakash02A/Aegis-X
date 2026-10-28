package com.aegisx.integrity.exception;

/**
 * A requested state transition is not permitted by the canonical lifecycle model.
 *
 * <p>Distinct from a <em>conflicting observation</em>: a conflicting observation is expected
 * provider behaviour and is recorded rather than rejected. This exception is raised only when code
 * tries to force a transition that no evidence can justify.</p>
 */
public class IllegalPaymentStateException extends RuntimeException {

    public IllegalPaymentStateException(String message) {
        super(message);
    }
}
