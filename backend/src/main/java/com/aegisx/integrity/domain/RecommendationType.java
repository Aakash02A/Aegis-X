package com.aegisx.integrity.domain;

/**
 * Required evidence before this recommendation may be raised, plus the safety category.
 */
public enum RecommendationType {

    /** Re-read the provider's current state for the affected resource. */
    FETCH_PROVIDER_STATE(RecommendationCategory.READ_ONLY),
    /** Re-run reconciliation for the workspace. */
    RERUN_RECONCILIATION(RecommendationCategory.READ_ONLY),
    /** A provider operation is legitimately still in flight; observe before acting. */
    WAIT_FOR_PENDING(RecommendationCategory.READ_ONLY),
    /** Compare provider and internal amounts in detail before deciding. */
    INVESTIGATE_AMOUNT_MISMATCH(RecommendationCategory.READ_ONLY),
    /** Inspect the refund records on the provider side. */
    INSPECT_REFUND(RecommendationCategory.READ_ONLY),
    /** Inspect the transfer records on the provider side. */
    INSPECT_TRANSFER(RecommendationCategory.READ_ONLY),
    /** Retry a read-only provider call that previously failed. */
    RETRY_READ_OPERATION(RecommendationCategory.READ_ONLY),
    /** A missing refund might exist on the provider: privileged purchase, still not automatic. */
    CREATE_REFUND(RecommendationCategory.FINANCIAL),
    /** A missing transfer might be required: privileged purchase, still not automatic. */
    CREATE_TRANSFER(RecommendationCategory.FINANCIAL),
    /** A human must decide how to map an unmatched provider payment. */
    ESCALATE_TO_HUMAN(RecommendationCategory.HUMAN_APPROVAL_REQUIRED),
    /** Finding is informational (for example a duplicate delivery); nothing to do. */
    NO_ACTION_REQUIRED(RecommendationCategory.READ_ONLY),

    // ---------------------------------------------------------------- evidence

    /** Ask the merchant for the document the order is missing. */
    REQUEST_MISSING_DOCUMENT(RecommendationCategory.HUMAN_APPROVAL_REQUIRED),
    /** Compare the invoice total against the order and payment before changing any record. */
    COMPARE_INVOICE_TOTAL(RecommendationCategory.READ_ONLY),
    /** Re-read the document, or ask for a legible copy if it cannot be read. */
    REQUEST_LEGIBLE_COPY(RecommendationCategory.HUMAN_APPROVAL_REQUIRED),
    /** A person must read the document and confirm or correct the values the engine relies on. */
    CONFIRM_EXTRACTED_VALUES(RecommendationCategory.HUMAN_APPROVAL_REQUIRED),
    /** Decide which of two documents is the real one; never guessed automatically. */
    RESOLVE_DUPLICATE_DOCUMENT(RecommendationCategory.HUMAN_APPROVAL_REQUIRED),
    /** The document may belong to a different payment; a person must confirm the association. */
    VERIFY_DOCUMENT_ASSOCIATION(RecommendationCategory.HUMAN_APPROVAL_REQUIRED);

    private final RecommendationCategory category;

    RecommendationType(RecommendationCategory category) {
        this.category = category;
    }

    public RecommendationCategory category() {
        return category;
    }

    public boolean isAutoExecutable() {
        return category == RecommendationCategory.READ_ONLY;
    }

    public boolean isFinancial() {
        return category == RecommendationCategory.FINANCIAL;
    }
}
