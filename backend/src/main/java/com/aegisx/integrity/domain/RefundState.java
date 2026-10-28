package com.aegisx.integrity.domain;

/**
 * Canonical refund state. Provider-supported values are {@code pending}, {@code processed} and {@code failed}.
 */
public enum RefundState {
    PENDING,
    PROCESSED,
    FAILED;

    public boolean isTerminal() {
        return this == PROCESSED || this == FAILED;
    }
}
