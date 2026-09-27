package com.aegisx.evidence.dto;

import com.aegisx.evidence.entity.EvidenceChecklist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ResponseDTO for Evidence Checklist information.
 * Used in REST API responses to provide checklist status to clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceChecklistResponse {

    private UUID id;
    private UUID transactionId;
    
    // Invoice requirements and status
    private boolean invoiceRequired;
    private boolean invoiceUploaded;
    
    // Shipping bill requirements and status
    private boolean shippingBillRequired;
    private boolean shippingBillUploaded;
    
    // Packing list requirements and status
    private boolean packingListRequired;
    private boolean packingListUploaded;
    
    // Certificate of origin requirements and status
    private boolean certificateOriginRequired;
    private boolean certificateOriginUploaded;
    
    // Overall satisfaction status
    private boolean satisfied;
    private LocalDateTime evaluatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Creates response DTO from entity.
     */
    public static EvidenceChecklistResponse fromEntity(EvidenceChecklist checklist) {
        return EvidenceChecklistResponse.builder()
                .id(checklist.getId())
                .transactionId(checklist.getTransaction().getId())
                .invoiceRequired(Boolean.TRUE.equals(checklist.getInvoiceRequired()))
                .invoiceUploaded(Boolean.TRUE.equals(checklist.getInvoiceUploaded()))
                .shippingBillRequired(Boolean.TRUE.equals(checklist.getShippingBillRequired()))
                .shippingBillUploaded(Boolean.TRUE.equals(checklist.getShippingBillUploaded()))
                .packingListRequired(Boolean.TRUE.equals(checklist.getPackingListRequired()))
                .packingListUploaded(Boolean.TRUE.equals(checklist.getPackingListUploaded()))
                .certificateOriginRequired(Boolean.TRUE.equals(checklist.getCertificateOriginRequired()))
                .certificateOriginUploaded(Boolean.TRUE.equals(checklist.getCertificateOriginUploaded()))
                .satisfied(checklist.isSatisfied())
                .evaluatedAt(checklist.getEvaluatedAt())
                .createdAt(java.time.LocalDateTime.ofInstant(checklist.getCreatedAt(), java.time.ZoneOffset.UTC))
                .updatedAt(java.time.LocalDateTime.ofInstant(checklist.getUpdatedAt(), java.time.ZoneOffset.UTC))
                .build();
    }

    /**
     * Calculates completion percentage.
     */
    public double getCompletionPercentage() {
        int required = 0;
        int completed = 0;

        if (invoiceRequired) {
            required++;
            if (invoiceUploaded) completed++;
        }

        if (shippingBillRequired) {
            required++;
            if (shippingBillUploaded) completed++;
        }

        if (packingListRequired) {
            required++;
            if (packingListUploaded) completed++;
        }

        if (certificateOriginRequired) {
            required++;
            if (certificateOriginUploaded) completed++;
        }

        return required == 0 ? 100.0 : (completed * 100.0 / required);
    }

    /**
     * Gets total number of required documents.
     */
    public int getTotalRequiredDocuments() {
        int count = 0;
        if (invoiceRequired) count++;
        if (shippingBillRequired) count++;
        if (packingListRequired) count++;
        if (certificateOriginRequired) count++;
        return count;
    }

    /**
     * Gets number of uploaded documents.
     */
    public int getUploadedDocumentsCount() {
        int count = 0;
        if (invoiceRequired && invoiceUploaded) count++;
        if (shippingBillRequired && shippingBillUploaded) count++;
        if (packingListRequired && packingListUploaded) count++;
        if (certificateOriginRequired && certificateOriginUploaded) count++;
        return count;
    }
}
