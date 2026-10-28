package com.aegisx.integrity.domain;

import java.util.Set;

/**
 * Canonical order state.
 *
 * <p>Provider-supported values are {@code created}, {@code attempted} and {@code paid}
 * (Razorpay Orders API). {@code EXPIRED} and {@code CANCELLED} are Aegis-X internal states used
 * only when the provider reports the order is no longer payable.</p>
 */
public enum OrderState {
    CREATED,
    ATTEMPTED,
    PAID,
    EXPIRED,
    CANCELLED;

    public boolean isTerminal() {
        return this == PAID || this == EXPIRED || this == CANCELLED;
    }

    public boolean isPayable() {
        return this == CREATED || this == ATTEMPTED;
    }

    public Set<OrderState> allowedNext() {
        return switch (this) {
            case CREATED -> Set.of(ATTEMPTED, PAID, EXPIRED, CANCELLED);
            case ATTEMPTED -> Set.of(PAID, EXPIRED, CANCELLED);
            case PAID, EXPIRED, CANCELLED -> Set.of();
        };
    }
}
