package com.aegisx.integrity.domain;

/**
 * Deterministic exception taxonomy produced by the reconciliation engine.
 *
 * <p>Every value is a *discrepancy between records*, never a fraud allegation. Fraud is
 * explicitly out of scope without independent evidence.</p>
 */
public enum FindingType {
    /** An internal order exists that the provider has no captured payment for. */
    INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT,
    /** A provider payment exists that cannot be matched to any internal order. */
    PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER,
    /** Provider-confirmed amount differs from the internal expected amount. */
    AMOUNT_MISMATCH,
    /** Provider-confirmed currency differs from the internal currency. */
    CURRENCY_MISMATCH,
    /** Aegis-X's internal status disagrees with higher-ranked provider evidence. */
    STATUS_MISMATCH,
    /** The same provider event id was delivered more than once. */
    DUPLICATE_EVENT_DELIVERY,
    /** A payment has been pending beyond the staleness threshold. */
    STALE_PAYMENT,
    /** Refund records disagree with the payment (amount, state or existence). */
    REFUND_DISCREPANCY,
    /** Transfer records disagree with the payment, or Route is reported as enabled but nothing moved. */
    TRANSFER_DISCREPANCY,
    /** Two observations of the same resource disagree. */
    CONFLICTING_OBSERVATIONS,

    // ------------------------------------------------------------------ evidence

    /**
     * A document type the order requires has no current document.
     *
     * <p>Evidence findings answer a different question from the ten above: not "do the provider and
     * the internal record agree about the money" but "is the paperwork that justifies this payment
     * present, readable and consistent". A successful payment whose invoice is missing is a
     * perfectly ordinary state, reported here rather than hidden.</p>
     */
    MISSING_REQUIRED_EVIDENCE,
    /** The total an evidence document states differs from the amount the order expects. */
    EVIDENCE_AMOUNT_MISMATCH,
    /** An evidence document is denominated in a currency other than the order's. */
    EVIDENCE_CURRENCY_MISMATCH,
    /** A document names an order or payment reference that does not belong to this payment. */
    EVIDENCE_REFERENCE_MISMATCH,
    /** Two current documents carry identical bytes, or two invoices state the same invoice number. */
    DUPLICATE_EVIDENCE_DOCUMENT,
    /** A value the engine needs for matching was read below the confidence threshold. */
    EVIDENCE_EXTRACTION_UNCERTAIN,
    /** A document could not be read, so its contents are unknown rather than absent. */
    EVIDENCE_UNREADABLE,
    /** A document is attached to an order that does not exist in this workspace. */
    EVIDENCE_ORPHANED

    ;

    /** True for the findings produced by the evidence dimension rather than the payment lifecycle. */
    public boolean isEvidenceFinding() {
        return switch (this) {
            case MISSING_REQUIRED_EVIDENCE, EVIDENCE_AMOUNT_MISMATCH, EVIDENCE_CURRENCY_MISMATCH,
                 EVIDENCE_REFERENCE_MISMATCH, DUPLICATE_EVIDENCE_DOCUMENT,
                 EVIDENCE_EXTRACTION_UNCERTAIN, EVIDENCE_UNREADABLE, EVIDENCE_ORPHANED -> true;
            default -> false;
        };
    }
}
