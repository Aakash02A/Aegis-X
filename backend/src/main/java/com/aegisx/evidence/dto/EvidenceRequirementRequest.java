package com.aegisx.evidence.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for updating evidence requirements.
 * Used by compliance officers or system administrators to modify document requirements.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRequirementRequest {

    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;
    
    @Builder.Default
    private Boolean invoiceRequired = true;
    
    @Builder.Default
    private Boolean shippingBillRequired = false;
    
    @Builder.Default
    private Boolean packingListRequired = false;
    
    @Builder.Default
    private Boolean certificateOriginRequired = false;
    
    private String reason;

    /**
     * Validates the requirement request.
     */
    public void validate() {
        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID cannot be null");
        }
        
        // At least one document must be required
        if (!Boolean.TRUE.equals(invoiceRequired) && 
            !Boolean.TRUE.equals(shippingBillRequired) && 
            !Boolean.TRUE.equals(packingListRequired) && 
            !Boolean.TRUE.equals(certificateOriginRequired)) {
            throw new IllegalArgumentException("At least one document type must be required");
        }
    }

    /**
     * Gets safe boolean values (null-safe).
     */
    public boolean isInvoiceRequiredSafe() {
        return Boolean.TRUE.equals(invoiceRequired);
    }

    public boolean isShippingBillRequiredSafe() {
        return Boolean.TRUE.equals(shippingBillRequired);
    }

    public boolean isPackingListRequiredSafe() {
        return Boolean.TRUE.equals(packingListRequired);
    }

    public boolean isCertificateOriginRequiredSafe() {
        return Boolean.TRUE.equals(certificateOriginRequired);
    }
}