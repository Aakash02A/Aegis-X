package com.aegisx.integrity.domain;

/**
 * Where an evidence document came from.
 *
 * <p>Recorded so the UI never presents a document a merchant uploaded as something the system
 * obtained from a payment provider, and vice versa.</p>
 */
public enum EvidenceDocumentOrigin {

    /** A person uploaded the file through the console, or the API on their behalf. */
    MERCHANT_UPLOAD,

    /** Obtained from a connected provider or business system rather than typed in by hand. */
    PROVIDER_ATTACHMENT,

    /** Produced by Aegis-X itself, for example an exported reconciliation statement. */
    SYSTEM_GENERATED
}
