package com.aegisx.settlement.entity;

/**
 * Enumeration of supported payment processors.
 * Each processor has specific integration requirements and capabilities.
 */
public enum PaymentProcessor {
    
    /**
     * SWIFT network for international wire transfers.
     */
    SWIFT("SWIFT", "Society for Worldwide Interbank Financial Telecommunication", 
          true, true, "MT103", 24),
    
    /**
     * Ripple network for cross-border payments.
     */
    RIPPLE("Ripple", "Ripple Network", 
           true, false, "ILP", 1),
    
    /**
     * Visa Direct for card-based transfers.
     */
    VISA_DIRECT("Visa Direct", "Visa Direct Push-to-Card", 
                false, false, "OCT", 1),
    
    /**
     * Mastercard Send for card-based transfers.
     */
    MASTERCARD_SEND("Mastercard Send", "Mastercard Send Person-to-Person", 
                     false, false, "MoneySend", 1),
    
    /**
     * ACH network for domestic US transfers.
     */
    ACH("ACH", "Automated Clearing House", 
        false, true, "CCD+", 72),
    
    /**
     * SEPA network for European transfers.
     */
    SEPA("SEPA", "Single Euro Payments Area", 
         false, true, "SCT", 24),
    
    /**
     * Faster Payments for UK domestic transfers.
     */
    FASTER_PAYMENTS("Faster Payments", "UK Faster Payments Service", 
                    false, false, "FPS", 1),
    
    /**
     * Mock processor for testing and development.
     */
    MOCK("Mock", "Mock Payment Processor for Testing", 
         true, false, "MOCK", 0);

    private final String code;
    private final String displayName;
    private final boolean supportsCrossBorder;
    private final boolean requiresCorrespondentBank;
    private final String messageFormat;
    private final int typicalSettlementHours;

    PaymentProcessor(String code, String displayName, boolean supportsCrossBorder, 
                    boolean requiresCorrespondentBank, String messageFormat, 
                    int typicalSettlementHours) {
        this.code = code;
        this.displayName = displayName;
        this.supportsCrossBorder = supportsCrossBorder;
        this.requiresCorrespondentBank = requiresCorrespondentBank;
        this.messageFormat = messageFormat;
        this.typicalSettlementHours = typicalSettlementHours;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean supportsCrossBorder() {
        return supportsCrossBorder;
    }

    public boolean requiresCorrespondentBank() {
        return requiresCorrespondentBank;
    }

    public String getMessageFormat() {
        return messageFormat;
    }

    public int getTypicalSettlementHours() {
        return typicalSettlementHours;
    }

    /**
     * Returns true if this processor supports real-time settlements.
     */
    public boolean isRealTime() {
        return typicalSettlementHours <= 1;
    }

    /**
     * Gets the processor by code.
     */
    public static PaymentProcessor fromCode(String code) {
        for (PaymentProcessor processor : values()) {
            if (processor.getCode().equalsIgnoreCase(code)) {
                return processor;
            }
        }
        throw new IllegalArgumentException("Unknown payment processor code: " + code);
    }

    /**
     * Returns processors that support cross-border payments.
     */
    public static PaymentProcessor[] getCrossBorderProcessors() {
        return java.util.Arrays.stream(values())
                .filter(PaymentProcessor::supportsCrossBorder)
                .toArray(PaymentProcessor[]::new);
    }

    /**
     * Returns real-time processors.
     */
    public static PaymentProcessor[] getRealTimeProcessors() {
        return java.util.Arrays.stream(values())
                .filter(PaymentProcessor::isRealTime)
                .toArray(PaymentProcessor[]::new);
    }
}