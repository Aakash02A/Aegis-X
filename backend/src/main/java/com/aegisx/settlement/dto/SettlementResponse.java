package com.aegisx.settlement.dto;

import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for Settlement information.
 * Used in REST API responses to provide settlement details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementResponse {

    private UUID id;
    private UUID transactionId;
    private String transactionReference;
    private String settlementReference;
    private PaymentProcessor paymentProcessor;
    private String processorDisplayName;
    private String processorTransactionId;
    private SettlementStatus status;
    private String statusDisplayName;
    private String statusDescription;
    
    private BigDecimal settlementAmount;
    private String settlementCurrency;
    private BigDecimal exchangeRate;
    private BigDecimal processingFees;
    private BigDecimal netAmount;
    
    private LocalDateTime initiatedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime expectedCompletionAt;
    private LocalDateTime reconciledAt;
    
    private String failureReason;
    private Integer retryCount;
    private Integer maxRetries;
    private LocalDateTime nextRetryAt;
    private String initiatedBy;
    
    // Status flags
    private boolean isFinal;
    private boolean isSuccessful;
    private boolean isFailed;
    private boolean isOverdue;
    private boolean canRetry;
    private boolean requiresManualIntervention;
    
    // Calculated fields
    private Long settlementDurationHours;
    private String formattedDuration;
    private String riskLevel;
    
    // Processor metadata (filtered for security)
    private JsonNode publicMetadata;

    /**
     * Creates response DTO from Settlement entity.
     */
    public static SettlementResponse fromEntity(Settlement settlement) {
        SettlementResponseBuilder builder = SettlementResponse.builder()
                .id(settlement.getId())
                .transactionId(settlement.getTransaction().getId())
                .transactionReference(settlement.getTransaction().getReferenceNumber())
                .settlementReference(settlement.getSettlementReference())
                .paymentProcessor(settlement.getPaymentProcessor())
                .processorDisplayName(settlement.getPaymentProcessor().getDisplayName())
                .processorTransactionId(settlement.getProcessorTransactionId())
                .status(settlement.getStatus())
                .statusDisplayName(settlement.getStatus().getDisplayName())
                .statusDescription(settlement.getStatus().getDescription())
                .settlementAmount(settlement.getSettlementAmount())
                .settlementCurrency(settlement.getSettlementCurrency())
                .exchangeRate(settlement.getExchangeRate())
                .processingFees(settlement.getProcessingFees())
                .netAmount(settlement.getNetAmount())
                .initiatedAt(settlement.getInitiatedAt())
                .submittedAt(settlement.getSubmittedAt())
                .completedAt(settlement.getCompletedAt())
                .expectedCompletionAt(settlement.getExpectedCompletionAt())
                .reconciledAt(settlement.getReconciledAt())
                .failureReason(settlement.getFailureReason())
                .retryCount(settlement.getRetryCount())
                .maxRetries(settlement.getMaxRetries())
                .nextRetryAt(settlement.getNextRetryAt())
                .initiatedBy(settlement.getInitiatedBy())
                .isFinal(settlement.isFinal())
                .isSuccessful(settlement.isSuccessful())
                .isFailed(settlement.isFailed())
                .isOverdue(settlement.isOverdue())
                .canRetry(settlement.canRetry())
                .requiresManualIntervention(settlement.getStatus().requiresManualIntervention())
                .settlementDurationHours(settlement.getSettlementDurationHours())
                .publicMetadata(filterMetadata(settlement.getProcessorMetadata()));

        // Add formatted duration
        Long durationHours = settlement.getSettlementDurationHours();
        if (durationHours != null) {
            builder.formattedDuration(formatDuration(durationHours));
        }

        // Add risk level assessment
        builder.riskLevel(assessRiskLevel(settlement));

        return builder.build();
    }

    /**
     * Filters processor metadata to remove sensitive information.
     */
    private static JsonNode filterMetadata(JsonNode metadata) {
        if (metadata == null) return null;
        
        // Clone and remove sensitive fields
        com.fasterxml.jackson.databind.node.ObjectNode filtered = metadata.deepCopy();
        
        // Remove sensitive fields that shouldn't be exposed in API
        filtered.remove(java.util.List.of(
                "apiKey",
                "credentials",
                "internalReference",
                "bankAccountDetails",
                "account_number",
                "contactId",
                "fundAccountId",
                "contact_id",
                "fund_account_id"));
        
        return filtered;
    }

    /**
     * Formats duration in human-readable format.
     */
    private static String formatDuration(Long hours) {
        if (hours == null || hours == 0) {
            return "Less than 1 hour";
        }
        
        if (hours < 24) {
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        
        long days = hours / 24;
        long remainingHours = hours % 24;
        
        String dayPart = days == 1 ? "1 day" : days + " days";
        
        if (remainingHours == 0) {
            return dayPart;
        }
        
        String hourPart = remainingHours == 1 ? "1 hour" : remainingHours + " hours";
        return dayPart + " " + hourPart;
    }

    /**
     * Assesses risk level based on settlement characteristics.
     */
    private static String assessRiskLevel(Settlement settlement) {
        if (settlement.getStatus().isError()) {
            return "HIGH";
        }
        
        if (settlement.isOverdue()) {
            return "MEDIUM";
        }
        
        if (settlement.getRetryCount() > 0) {
            return "MEDIUM";
        }
        
        if (settlement.getStatus().requiresManualIntervention()) {
            return "HIGH";
        }
        
        return "LOW";
    }

    /**
     * Gets formatted status with icon.
     */
    public String getFormattedStatus() {
        String icon = switch (status) {
            case COMPLETED, RECONCILED -> "✅";
            case PROCESSING, SUBMITTED, PENDING -> "⏳";
            case FAILED, REJECTED -> "❌";
            case CANCELLED -> "🚫";
            case MANUAL_REVIEW -> "👥";
            default -> "ℹ️";
        };
        return icon + " " + statusDisplayName;
    }

    /**
     * Gets formatted amount with currency.
     */
    public String getFormattedAmount() {
        return String.format("%.2f %s", settlementAmount, settlementCurrency);
    }

    /**
     * Gets formatted net amount with currency.
     */
    public String getFormattedNetAmount() {
        return String.format("%.2f %s", netAmount, settlementCurrency);
    }

    /**
     * Returns true if settlement is in progress.
     */
    public boolean isInProgress() {
        return !isFinal;
    }

    /**
     * Gets completion percentage for progress tracking.
     */
    public int getCompletionPercentage() {
        return switch (status) {
            case INITIATED -> 10;
            case PROCESSING -> 25;
            case SUBMITTED -> 50;
            case PENDING -> 75;
            case COMPLETED -> 90;
            case RECONCILED -> 100;
            case RECONCILING -> 95;
            default -> isFailed ? 0 : 100;
        };
    }

    /**
     * Gets status color for UI display.
     */
    public String getStatusColor() {
        return switch (status) {
            case COMPLETED, RECONCILED -> "#10B981"; // Green
            case PROCESSING, SUBMITTED, PENDING, RECONCILING -> "#F59E0B"; // Amber
            case FAILED, REJECTED, RECONCILIATION_FAILED -> "#EF4444"; // Red
            case CANCELLED -> "#6B7280"; // Gray
            case MANUAL_REVIEW -> "#8B5CF6"; // Purple
            default -> "#3B82F6"; // Blue
        };
    }

    /**
     * Gets processor icon/logo identifier.
     */
    public String getProcessorIcon() {
        return switch (paymentProcessor) {
            case RAZORPAYX -> "razorpay";
            case SIMULATED, MOCK -> "simulated";
            default -> "unsupported";
        };
    }

    /**
     * Returns true if settlement supports real-time tracking.
     */
    public boolean supportsRealTimeTracking() {
        return paymentProcessor.isRealTime();
    }

    /**
     * Gets estimated completion time if not set.
     */
    public LocalDateTime getEstimatedCompletion() {
        if (expectedCompletionAt != null) {
            return expectedCompletionAt;
        }
        
        if (initiatedAt != null) {
            return initiatedAt.plusHours(paymentProcessor.getTypicalSettlementHours());
        }
        
        return null;
    }
}