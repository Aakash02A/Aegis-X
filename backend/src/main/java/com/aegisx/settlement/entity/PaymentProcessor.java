package com.aegisx.settlement.entity;

/**
 * Processor identifiers retained for stored settlement compatibility.
 * Only RazorpayX and the explicitly simulated path accept new settlements.
 */
public enum PaymentProcessor {
    
    SWIFT("SWIFT", "Unsupported legacy processor (no provider integration)",
          false, false, "UNSUPPORTED", 0),
    RIPPLE("Ripple", "Unsupported legacy processor (no provider integration)",
           false, false, "UNSUPPORTED", 0),
    VISA_DIRECT("Visa Direct", "Unsupported legacy processor (no provider integration)",
                false, false, "UNSUPPORTED", 0),
    MASTERCARD_SEND("Mastercard Send", "Unsupported legacy processor (no provider integration)",
                    false, false, "UNSUPPORTED", 0),
    ACH("ACH", "Unsupported legacy processor (no provider integration)",
        false, false, "UNSUPPORTED", 0),
    SEPA("SEPA", "Unsupported legacy processor (no provider integration)",
         false, false, "UNSUPPORTED", 0),
    FASTER_PAYMENTS("Faster Payments", "Unsupported legacy processor (no provider integration)",
                    false, false, "UNSUPPORTED", 0),

    RAZORPAYX("RazorpayX", "RazorpayX India Payouts",
              false, false, "PAYOUT", 24),

    MOCK("Mock", "SIMULATED (legacy; no money moved)",
         false, false, "UNSUPPORTED", 0),
    SIMULATED("SIMULATED", "SIMULATED (no money moved)",
              false, false, "SIMULATED", 0);

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

    public boolean supportsNewSettlements() {
        return this == RAZORPAYX || this == SIMULATED;
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