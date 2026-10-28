package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.domain.EvidenceDocumentOrigin;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Module I: a supporting document attached to a payment integrity order.
 *
 * <p>A document is evidence about a payment, never a payment itself. Nothing in this entity can
 * change {@link PaymentRecord#getState()} or {@link PaymentRecord#getProviderState()} — associating
 * an invoice cannot make an uncaptured payment captured, and a document mismatch cannot release or
 * refund funds.</p>
 *
 * <p>Documents are superseded, never deleted. Re-uploading a correct invoice for an order that
 * already has one marks the old row as superseded and keeps both, so the record shows that a
 * replacement happened rather than silently losing the original.</p>
 *
 * <p>The {@code extracted*} columns hold the best currently-known values: written by the extraction
 * pipeline, then optionally replaced by an operator correction. The full history of how those values
 * were arrived at lives in {@code pi_evidence_extractions}.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_evidence_documents")
public class EvidenceDocument extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** Optional link to the payment this document is about; null when it concerns the order only. */
    @Column(name = "payment_id")
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 50)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "origin", nullable = false, length = 30)
    private EvidenceDocumentOrigin origin = EvidenceDocumentOrigin.MERCHANT_UPLOAD;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 255)
    private String storedFilename;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /** SHA-256 of the stored bytes. Drives real duplicate detection. */
    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    /** Human-readable actor, so the audit trail survives a deleted user row. */
    @Column(name = "actor_label", nullable = false, length = 120)
    private String actorLabel;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "processing_status", nullable = false, length = 30)
    private EvidenceProcessingStatus processingStatus = EvidenceProcessingStatus.RECEIVED;

    /** Why extraction failed, in plain language. Never a stack trace or a provider payload. */
    @Column(name = "processing_error", length = 500)
    private String processingError;

    @Column(name = "extracted_invoice_number", length = 120)
    private String extractedInvoiceNumber;

    @Column(name = "extracted_invoice_date")
    private LocalDate extractedInvoiceDate;

    /** Minor units, as everywhere else in the integrity module. */
    @Column(name = "extracted_amount_minor")
    private Long extractedAmountMinor;

    @Column(name = "extracted_currency", length = 3)
    private String extractedCurrency;

    @Column(name = "extracted_order_reference", length = 120)
    private String extractedOrderReference;

    @Column(name = "extracted_payment_reference", length = 120)
    private String extractedPaymentReference;

    /** True once a human has confirmed or corrected the values this document is matched on. */
    @Builder.Default
    @Column(name = "fields_confirmed", nullable = false)
    private boolean fieldsConfirmed = false;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** The document this one replaced, if any. */
    @Column(name = "supersedes_document_id")
    private UUID supersedesDocumentId;

    /** Which document replaced this one. Provenance only — {@link #isActive()} does not read it. */
    @Column(name = "superseded_by_document_id")
    private UUID supersededByDocumentId;

    /**
     * When this document stopped being current. This is the <em>authoritative</em> marker of whether
     * the row is active, and the unique index that allows only one active document per
     * (order, type) is built on it.
     *
     * <p>It is deliberately a different column from {@link #supersededByDocumentId}, and the reason is
     * a genuine circular dependency that only shows up on a database that enforces the invariant:
     * the replacement row cannot be inserted while the old row still occupies the single active slot,
     * so the old row must stop being current first; but {@code superseded_by_document_id} is a
     * foreign key, so the old row cannot name its replacement until that row exists. One column
     * cannot express both facts in an executable order. Two columns can: the row is first marked as
     * no longer current, then the replacement is inserted, then the replacement is named.</p>
     *
     * <p>The pair is kept consistent by a CHECK constraint that forbids naming a replacement without
     * also being marked superseded, and by always writing both in one transaction.</p>
     */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;

    /** True when this row is the current document for its (order, type). */
    public boolean isActive() {
        return supersededAt == null;
    }

    /**
     * Marks this document as no longer current. Deliberately does <em>not</em> require the
     * replacement's id, so it can be applied before that row exists.
     */
    public void markSuperseded() {
        this.supersededAt = Instant.now();
    }

    /** Records which document replaced this one. Provenance only; see {@link #supersededAt}. */
    public void supersedeWith(UUID replacementId) {
        this.supersededByDocumentId = replacementId;
    }
}
