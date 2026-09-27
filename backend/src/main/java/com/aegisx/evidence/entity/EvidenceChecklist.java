package com.aegisx.evidence.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.transaction.entity.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Evidence Checklist Entity - tracks document requirements and satisfaction per transaction.
 * Implements the evidence checklist specification from TAS Section 6.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "evidence_checklists")
public class EvidenceChecklist extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    @Column(name = "invoice_required", nullable = false)
    @Builder.Default
    private Boolean invoiceRequired = true;

    @Column(name = "invoice_uploaded", nullable = false)
    @Builder.Default
    private Boolean invoiceUploaded = false;

    @Column(name = "shipping_bill_required", nullable = false)
    @Builder.Default
    private Boolean shippingBillRequired = false;

    @Column(name = "shipping_bill_uploaded", nullable = false)
    @Builder.Default
    private Boolean shippingBillUploaded = false;

    @Column(name = "packing_list_required", nullable = false)
    @Builder.Default
    private Boolean packingListRequired = false;

    @Column(name = "packing_list_uploaded", nullable = false)
    @Builder.Default
    private Boolean packingListUploaded = false;

    @Column(name = "certificate_origin_required", nullable = false)
    @Builder.Default
    private Boolean certificateOriginRequired = false;

    @Column(name = "certificate_origin_uploaded", nullable = false)
    @Builder.Default
    private Boolean certificateOriginUploaded = false;

    @Column(name = "is_satisfied", nullable = false)
    @Builder.Default
    private Boolean isSatisfied = false;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    /**
     * Evaluates whether all required documents have been uploaded.
     * Updates the isSatisfied flag based on current requirements and upload status.
     */
    public void evaluateSatisfaction() {
        boolean satisfied = true;

        if (Boolean.TRUE.equals(invoiceRequired) && !Boolean.TRUE.equals(invoiceUploaded)) {
            satisfied = false;
        }
        if (Boolean.TRUE.equals(shippingBillRequired) && !Boolean.TRUE.equals(shippingBillUploaded)) {
            satisfied = false;
        }
        if (Boolean.TRUE.equals(packingListRequired) && !Boolean.TRUE.equals(packingListUploaded)) {
            satisfied = false;
        }
        if (Boolean.TRUE.equals(certificateOriginRequired) && !Boolean.TRUE.equals(certificateOriginUploaded)) {
            satisfied = false;
        }

        this.isSatisfied = satisfied;
        this.evaluatedAt = LocalDateTime.now();
    }

    /**
     * Marks a specific document type as uploaded.
     */
    public void markDocumentUploaded(DocumentType documentType) {
        DocumentType canonicalType = documentType.getCanonicalType();
        switch (canonicalType) {
            case COMMERCIAL_INVOICE -> this.invoiceUploaded = true;
            case SHIPPING_BILL -> this.shippingBillUploaded = true;
            case PACKING_LIST -> this.packingListUploaded = true;
            case CERTIFICATE_OF_ORIGIN -> this.certificateOriginUploaded = true;
            default -> throw new IllegalArgumentException("Unsupported document type: " + documentType);
        }
        evaluateSatisfaction();
    }

    /**
     * Sets requirements based on transaction characteristics.
     * Called during transaction initialization to determine required documents.
     */
    public void setRequirementsBasedOnTransaction(Transaction transaction) {
        // Default requirements - can be enhanced based on transaction properties
        this.invoiceRequired = true;
        
        // Shipping bill required for transactions > $5000
        if (transaction.getAmount().compareTo(new java.math.BigDecimal("5000")) > 0) {
            this.shippingBillRequired = true;
        }
        
        // Additional requirements can be added based on purpose code, country, etc.
        evaluateSatisfaction();
    }

    /**
     * Returns true if all requirements are satisfied.
     */
    public boolean isSatisfied() {
        return Boolean.TRUE.equals(this.isSatisfied);
    }
}