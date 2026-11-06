package com.aegisx.settlement.dto;

import com.aegisx.settlement.entity.PaymentProcessor;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for initiating settlements.
 * Used when creating new settlement requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRequest {

    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;

    @NotNull(message = "Payment processor is required")
    private PaymentProcessor paymentProcessor;

    @NotNull(message = "Settlement amount is required")
    @Digits(integer = 13, fraction = 2, message = "Invalid amount format")
    private BigDecimal settlementAmount;

    @NotBlank(message = "Settlement currency is required")
    @Size(min = 3, max = 3, message = "Currency code must be 3 characters")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency code must be uppercase ISO format")
    private String settlementCurrency;

    @DecimalMin(value = "0.000001", message = "Exchange rate must be positive")
    @Digits(integer = 4, fraction = 6, message = "Invalid exchange rate format")
    private BigDecimal exchangeRate;

    @DecimalMin(value = "0.00", message = "Processing fees cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "Invalid fee format")
    private BigDecimal processingFees;

    private String beneficiaryAccountNumber;
    private String beneficiaryBankCode;
    private String beneficiaryName;
    private String beneficiaryAddress;

    private String remitterAccountNumber;
    private String remitterBankCode;
    private String remitterName;

    @Size(max = 200, message = "Payment purpose too long")
    private String paymentPurpose;

    @Size(max = 500, message = "Settlement instructions too long")
    private String settlementInstructions;

    private Map<String, Object> additionalData;

    private boolean prioritySettlement;
    private boolean requiresReconciliation;

    /**
     * Validates the settlement request.
     */
    public void validate() {
        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID is required");
        }
        
        if (paymentProcessor == null) {
            throw new IllegalArgumentException("Payment processor is required");
        }
        
        if (settlementAmount == null || settlementAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Settlement amount must be positive");
        }
        
        if (settlementCurrency == null || settlementCurrency.length() != 3) {
            throw new IllegalArgumentException("Valid 3-letter currency code is required");
        }
        
        // Processor-specific validations
        validateProcessorSpecificRequirements();
    }

    /**
     * Validates processor-specific requirements.
     */
    private void validateProcessorSpecificRequirements() {
        switch (paymentProcessor) {
            case RAZORPAYX -> {
                if (!"INR".equals(settlementCurrency)) {
                    throw new IllegalArgumentException("RazorpayX payouts require INR settlement currency");
                }
                if (beneficiaryName == null || !beneficiaryName.trim().matches("^[A-Za-z0-9 ._()/'-]{3,50}$")) {
                    throw new IllegalArgumentException("Valid beneficiary name is required for RazorpayX");
                }
                if (beneficiaryAccountNumber == null || !beneficiaryAccountNumber.matches("^[A-Za-z0-9]{5,35}$")) {
                    throw new IllegalArgumentException("Valid beneficiary bank account number is required for RazorpayX");
                }
                if (beneficiaryBankCode == null || !beneficiaryBankCode.matches("^[A-Z]{4}0[A-Z0-9]{6}$")) {
                    throw new IllegalArgumentException("A valid 11-character IFSC is required for RazorpayX");
                }
                if (settlementAmount.compareTo(new BigDecimal("1.00")) < 0) {
                    throw new IllegalArgumentException("RazorpayX payouts must be at least INR 1.00");
                }
            }
            default -> {
            }
        }
    }

    /**
     * Returns true if cross-border settlement.
     */
    public boolean isCrossBorder() {
        return paymentProcessor.supportsCrossBorder();
    }

    /**
     * Gets estimated settlement time in hours.
     */
    public int getEstimatedSettlementHours() {
        int baseHours = paymentProcessor.getTypicalSettlementHours();
        return prioritySettlement ? Math.max(1, baseHours / 2) : baseHours;
    }

    /**
     * Gets net settlement amount after fees.
     */
    public BigDecimal getNetSettlementAmount() {
        if (processingFees == null) {
            return settlementAmount;
        }
        return settlementAmount.subtract(processingFees);
    }

    /**
     * Creates a basic settlement request for testing.
     */
    public static SettlementRequest createBasic(UUID transactionId, 
                                              PaymentProcessor processor, 
                                              BigDecimal amount, 
                                              String currency) {
        return SettlementRequest.builder()
                .transactionId(transactionId)
                .paymentProcessor(processor)
                .settlementAmount(amount)
                .settlementCurrency(currency)
                .requiresReconciliation(true)
                .build();
    }

}