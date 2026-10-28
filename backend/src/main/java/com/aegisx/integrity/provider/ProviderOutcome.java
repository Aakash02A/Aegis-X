package com.aegisx.integrity.provider;

import com.aegisx.integrity.domain.CapabilityStatus;

/**
 * Result of one adapter call.
 *
 * <p>An adapter never throws to signal "the provider is not configured" or "the provider is
 * unreachable": those are ordinary outcomes that reconciliation must be able to reason about and
 * record as evidence. Genuine programming errors still throw.</p>
 *
 * @param status   outcome classification; {@link CapabilityStatus#OK} or
 *                 {@link CapabilityStatus#SIMULATED} carry a value
 * @param value    the resource snapshot, when one was obtained
 * @param detail   short non-secret explanation, safe to persist and display
 * @param simulated true when the value came from the simulated adapter
 */
public record ProviderOutcome<T>(
        CapabilityStatus status,
        T value,
        String detail,
        boolean simulated) {

    public static <T> ProviderOutcome<T> ok(T value, boolean simulated) {
        return new ProviderOutcome<>(simulated ? CapabilityStatus.SIMULATED : CapabilityStatus.OK, value, null, simulated);
    }

    public static <T> ProviderOutcome<T> notConfigured(String detail) {
        return new ProviderOutcome<>(CapabilityStatus.NOT_CONFIGURED, null, detail, false);
    }

    public static <T> ProviderOutcome<T> notEnabled(String detail) {
        return new ProviderOutcome<>(CapabilityStatus.NOT_ENABLED, null, detail, false);
    }

    public static <T> ProviderOutcome<T> unavailable(String detail, boolean simulated) {
        return new ProviderOutcome<>(CapabilityStatus.UNAVAILABLE, null, detail, simulated);
    }

    /** The provider answered definitively that the resource does not exist. */
    public static <T> ProviderOutcome<T> notFound(String detail, boolean simulated) {
        return new ProviderOutcome<>(CapabilityStatus.NOT_FOUND, null, detail, simulated);
    }

    /** Whether the provider affirmatively reported the resource as absent. */
    public boolean isDefinitelyAbsent() {
        return status == CapabilityStatus.NOT_FOUND;
    }

    /** Whether the answer is unknown because the provider could not be reached. */
    public boolean isIndeterminate() {
        return status == CapabilityStatus.UNAVAILABLE || status == CapabilityStatus.NOT_CONFIGURED;
    }

    /** True when a usable snapshot was returned. */
    public boolean hasValue() {
        return value != null && (status == CapabilityStatus.OK || status == CapabilityStatus.SIMULATED);
    }
}
