package com.aegisx.integrity.benchmark;

/**
 * The controlled failure modes of the research benchmark.
 *
 * <p>These mirror the fault catalogue in the specification. Every fault is injected into a
 * <em>generated scenario</em> — never into a real provider transaction, which the specification
 * forbids and which would be neither reproducible nor ethical.</p>
 */
public enum FaultKind {
    /** Legitimate lifecycle: order paid, payment captured, provider and internal records agree. */
    NORMAL_COMPLETION(false),
    /** The provider re-delivers an event it already sent. */
    DUPLICATE_WEBHOOK(false),
    /** `payment.failed` arrives after `payment.captured` has already been confirmed. */
    OUT_OF_ORDER_WEBHOOK(false),
    /** The capture webhook is late, so the internal record lags the provider's real state. */
    DELAYED_WEBHOOK(false),
    /** Our request timed out: the true outcome is unknown until a lookup happens. */
    TIMEOUT_UNKNOWN_OUTCOME(false),
    /** Internal status disagrees with provider-confirmed status. */
    STATUS_MISMATCH(false),
    /** Provider-confirmed amount differs from the internal expected amount. */
    AMOUNT_MISMATCH(false),
    /** The provider has no payment for an order Aegis-X still considers open. */
    MISSING_PROVIDER_OBSERVATION(false),
    /** Refund records disagree with the payment, or the provider reports a refund we do not know. */
    REFUND_DISCREPANCY(true),
    /** Transfer records disagree with the captured payment, or Route is not enabled. */
    TRANSFER_DISCREPANCY(true),

    // ------------------------------------------------------------------ evidence

    /**
     * The payment agrees and all required paperwork is present, readable and consistent.
     *
     * <p>Exists for the same reason {@link #NORMAL_COMPLETION} does: a rule that never fires is as
     * much a defect as one that never stops firing, so the clean case is measured rather than
     * assumed.</p>
     */
    EVIDENCE_CLEAN(false),
    /** A required document type — the commercial invoice — has no current document. */
    EVIDENCE_MISSING_INVOICE(false),
    /** The invoice exists but could not be read, so its contents are unknown rather than absent. */
    EVIDENCE_UNREADABLE_DOCUMENT(false),
    /** The total stated on a document disagrees with the amount the order expects. */
    EVIDENCE_AMOUNT_MISMATCH(false),
    /** A document is denominated in a currency other than the order's. */
    EVIDENCE_CURRENCY_MISMATCH(false),
    /** A document names an order or payment reference that belongs to neither. */
    EVIDENCE_REFERENCE_MISMATCH(false),
    /** Two current documents hold byte-identical content. */
    EVIDENCE_DUPLICATE_DOCUMENT(false),
    /** Values the checks rely on were read below the confidence threshold and no one confirmed them. */
    EVIDENCE_EXTRACTION_UNCERTAIN(false);

    private final boolean twoVariants;

    FaultKind(boolean twoVariants) {
        this.twoVariants = twoVariants;
    }

    public boolean hasTwoVariants() {
        return twoVariants;
    }

    /** Whether this kind injects a paperwork fault rather than a payment-lifecycle fault. */
    public boolean isEvidenceKind() {
        return name().startsWith("EVIDENCE_");
    }
}
