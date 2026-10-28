package com.aegisx.integrity.domain;

import java.util.Set;

/**
 * Canonical payment state.
 *
 * <p>Provider-supported values are {@code created}, {@code authorized}, {@code captured},
 * {@code refunded} and {@code failed}. {@code PARTIALLY_REFUNDED} and {@code TIMEOUT_UNKNOWN}
 * are Aegis-X internal states.</p>
 *
 * <p>{@code TIMEOUT_UNKNOWN} exists specifically so that an internal timeout is never conflated
 * with a provider-confirmed failure. It can only be resolved by a provider API lookup.</p>
 */
public enum PaymentState {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    /** Internal only: our request failed/timed out and we do not know the provider outcome. */
    TIMEOUT_UNKNOWN;

    /** Terminal states are never reverted by a later observation, whatever its source. */
    public boolean isTerminal() {
        return this == CAPTURED || this == REFUNDED || this == FAILED;
    }

    /** A provider-confirmed successful outcome. */
    public boolean isConfirmedSuccess() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }

    public boolean isPending() {
        return this == CREATED || this == AUTHORIZED || this == TIMEOUT_UNKNOWN;
    }

    public Set<PaymentState> allowedNext() {
        return switch (this) {
            case CREATED -> Set.of(AUTHORIZED, CAPTURED, FAILED, TIMEOUT_UNKNOWN);
            case AUTHORIZED -> Set.of(CAPTURED, FAILED, TIMEOUT_UNKNOWN);
            case TIMEOUT_UNKNOWN -> Set.of(CAPTURED, FAILED, AUTHORIZED);
            case CAPTURED -> Set.of(PARTIALLY_REFUNDED, REFUNDED);
            case PARTIALLY_REFUNDED -> Set.of(PARTIALLY_REFUNDED, REFUNDED);
            case REFUNDED, FAILED -> Set.of();
        };
    }
}
