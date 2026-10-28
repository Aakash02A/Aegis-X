package com.aegisx.integrity.domain;

/**
 * The processing lifecycle of a single evidence document.
 *
 * <p>Deliberately distinct from {@link EvidenceReadinessStatus}: this describes one document, the
 * readiness status describes the whole order.</p>
 */
public enum EvidenceProcessingStatus {

    /** Stored and hash-verified; extraction has not started. */
    RECEIVED,

    /** Extraction is running. */
    PROCESSING,

    /** Extraction finished and produced structured values (which may still be uncertain). */
    EXTRACTED,

    /** Extraction could not read the document. The reason is persisted, never guessed at. */
    FAILED;

    /** True when the document is still awaiting a result, so readiness is not yet decidable. */
    public boolean isInFlight() {
        return this == RECEIVED || this == PROCESSING;
    }

    /** True when extraction produced values that matching may rely on. */
    public boolean isUsable() {
        return this == EXTRACTED;
    }
}
