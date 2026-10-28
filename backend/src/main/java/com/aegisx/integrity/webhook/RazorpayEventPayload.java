package com.aegisx.integrity.webhook;

import java.time.Instant;

/**
 * A webhook delivery after parsing: only the fields Aegis-X actually needs.
 *
 * <p>Parsed <em>after</em> signature verification, never before. No card data is extracted because
 * the provider does not send any in these events and Aegis-X must not store what it does not need.</p>
 */
public record RazorpayEventPayload(
        String providerEventId,
        String eventType,
        String providerPaymentId,
        String providerOrderId,
        String providerRefundId,
        String providerTransferId,
        String state,
        Long amountMinor,
        String currency,
        String method,
        String errorDescription,
        Instant providerCreatedAt,
        String receipt) {

    public boolean hasPayment() {
        return providerPaymentId != null;
    }

    public boolean hasOrder() {
        return providerOrderId != null;
    }

    public boolean hasRefund() {
        return providerRefundId != null;
    }
}
