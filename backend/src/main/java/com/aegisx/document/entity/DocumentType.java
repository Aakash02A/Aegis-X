package com.aegisx.document.entity;

/**
 * Document types supported by the evidence management system.
 * Updated to match TAS specification Section 6 - Evidence Documents.
 */
public enum DocumentType {
    // Core document types from TAS specification
    COMMERCIAL_INVOICE("Commercial Invoice", "COMM_INV", true),
    SHIPPING_BILL("Shipping Bill", "SHIP_BILL", false),
    PACKING_LIST("Packing List", "PACK_LIST", false),
    CERTIFICATE_OF_ORIGIN("Certificate of Origin", "CERT_ORIG", false),
    
    // Legacy types for backward compatibility
    INVOICE("Invoice", "INV", true),
    SHIPPING_PROOF("Shipping Proof", "SHIP_PROOF", false),
    EXPORT_DECLARATION("Export Declaration", "EXP_DECL", false),
    BENEFICIARY_PROOF("Beneficiary Proof", "BEN_PROOF", false),
    OTHER("Other Document", "OTHER", false);

    private final String displayName;
    private final String shortCode;
    private final boolean mandatoryByDefault;

    DocumentType(String displayName, String shortCode, boolean mandatoryByDefault) {
        this.displayName = displayName;
        this.shortCode = shortCode;
        this.mandatoryByDefault = mandatoryByDefault;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortCode() {
        return shortCode;
    }

    public boolean isMandatoryByDefault() {
        return mandatoryByDefault;
    }

    /**
     * Returns true if this document type is typically required for cross-border transactions.
     */
    public boolean isTypicallyRequired() {
        return mandatoryByDefault;
    }

    /**
     * Gets document type from string value (case-insensitive).
     */
    public static DocumentType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Document type cannot be null");
        }
        
        try {
            return DocumentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid document type: " + value);
        }
    }

    /**
     * Maps legacy INVOICE type to COMMERCIAL_INVOICE for compatibility.
     */
    public DocumentType getCanonicalType() {
        return switch (this) {
            case INVOICE -> COMMERCIAL_INVOICE;
            case SHIPPING_PROOF -> SHIPPING_BILL;
            default -> this;
        };
    }

    /**
     * Returns true if this is a core document type from TAS specification.
     */
    public boolean isCoreType() {
        return this == COMMERCIAL_INVOICE || this == SHIPPING_BILL || 
               this == PACKING_LIST || this == CERTIFICATE_OF_ORIGIN;
    }
}
