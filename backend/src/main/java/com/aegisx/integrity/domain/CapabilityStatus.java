package com.aegisx.integrity.domain;

/**
 * Outcome of a provider capability or connectivity probe. Never carries credential values.
 */
public enum CapabilityStatus {
    /** Reachable and answering. */
    OK,
    /** Credentials are absent from the environment. */
    NOT_CONFIGURED,
    /** Credentials exist but the account does not have this product enabled (for example Route). */
    NOT_ENABLED,
    /** Reachable host but the call failed (network, 5xx, auth rejection). */
    UNAVAILABLE,
    /**
     * The provider answered definitively that the resource does not exist.
     *
     * <p>Kept strictly distinct from {@link #UNAVAILABLE}: an outage must never be interpreted as
     * "the provider has no record of this payment".</p>
     */
    NOT_FOUND,
    /** Historical status retained for compatibility with persisted simulated probe results. */
    SIMULATED
}
