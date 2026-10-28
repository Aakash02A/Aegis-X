package com.aegisx.integrity.domain;

/**
 * Which {@code PaymentProviderAdapter} implementation a workspace is bound to.
 */
public enum ProviderMode {
    /** Real Razorpay Test Mode API over HTTPS (requires configured test credentials). */
    RAZORPAY_TEST,
    /** Historical mode retained for persisted workspaces; no runtime adapter is provided. */
    SIMULATED;

    public boolean isSimulated() {
        return this == SIMULATED;
    }
}
