package com.aegisx.integrity.domain;

/**
 * Whether the supporting record set for a payment is complete, readable and mutually consistent.
 *
 * <p>This is a <strong>separate dimension</strong> from {@link PaymentState}. A payment can be
 * provider-confirmed {@code CAPTURED} while its evidence readiness is
 * {@link #PENDING_EVIDENCE}: a successful payment does not mean the order was fulfilled or that its
 * invoice exists. The two values are never derived from one another and neither overwrites the
 * other.</p>
 *
 * <p>Entry and exit conditions, applied in the order listed (first match wins):</p>
 * <ol>
 *   <li>{@link #PROCESSING} — entry: at least one active document has been received but its
 *       extraction has not finished. This outranks {@link #PROCESSING_FAILED} because while work is
 *       still in flight the outcome is genuinely unknown. exit: every active document reaches
 *       {@code EXTRACTED} or {@code FAILED}.</li>
 *   <li>{@link #PROCESSING_FAILED} — entry: nothing is in flight and at least one active document's
 *       extraction attempt ended in failure. exit: a later extraction attempt on that document
 *       succeeds, or the document is superseded by a readable one.</li>
 *   <li>{@link #PENDING_EVIDENCE} — entry: nothing is in flight, nothing has failed, and no document
 *       at all has been received for an order that requires evidence. exit: any document is
 *       received. When the order requires no documents this state is skipped entirely and the
 *       result is {@link #EVIDENCE_COMPLETE}.</li>
 *   <li>{@link #ACTION_REQUIRED} — entry: extraction is finished, but a required document is
 *       absent, or a document is unreadable, duplicated or describes a different order, or a
 *       detected amount/currency/reference disagreement is unresolved. exit: the missing documents
 *       are supplied, or the disagreement is corrected and re-assessed.</li>
 *   <li>{@link #READY_FOR_REVIEW} — entry: every required document is present and readable, but at
 *       least one value needed for matching was extracted below the confidence threshold, so a
 *       human has to confirm it. exit: the field is confirmed or corrected and the assessment is
 *       re-run.</li>
 *   <li>{@link #EVIDENCE_COMPLETE} — entry: every required document is present, readable, free of
 *       unresolved disagreement, and every value needed for matching is confirmed. exit: a new
 *       document is associated or removed, a document is superseded, or a later assessment detects
 *       a problem.</li>
 * </ol>
 */
public enum EvidenceReadinessStatus {

    /** The order requires supporting evidence and none has been received yet. */
    PENDING_EVIDENCE,

    /** Evidence has been received and extraction is still running. */
    PROCESSING,

    /** Every required document is present and readable; a human must confirm an uncertain value. */
    READY_FOR_REVIEW,

    /** Every required document is present, readable and consistent with the order and payment. */
    EVIDENCE_COMPLETE,

    /** Something the merchant must fix: missing, unreadable, duplicated or contradicting evidence. */
    ACTION_REQUIRED,

    /** Extraction failed for at least one document, so its contents are unknown. */
    PROCESSING_FAILED
}
