package com.aegisx.integrity.domain;

/**
 * Lifecycle of an exception case.
 *
 * <p>{@code RESOLVED} is not final: a later reconciliation run that still observes the discrepancy
 * automatically reopens the case. That reopen behaviour is the verification step of the required
 * end-to-end workflow.</p>
 */
public enum ExceptionStatus {
    OPEN,
    IN_REVIEW,
    RESOLVED,
    DISMISSED;

    public boolean isOpenForWork() {
        return this == OPEN || this == IN_REVIEW;
    }
}
