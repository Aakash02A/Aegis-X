package com.aegisx.integrity.domain;

/**
 * Canonical Route transfer state.
 *
 * <p>Only reachable when the provider account has Route enabled. Transfers require the source
 * payment to be in {@code captured} state.</p>
 */
public enum TransferState {
    CREATED,
    PENDING,
    PROCESSED,
    FAILED,
    REVERSED;

    public boolean isTerminal() {
        return this == PROCESSED || this == FAILED || this == REVERSED;
    }
}
