package com.aegisx.review.dto;

import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for review queue items.
 * Provides comprehensive transaction information for compliance officer review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewQueueItemResponse {

    private UUID transactionId;
    private String referenceNumber;
    private TransactionStatus status;
    private BigDecimal amount;
    private String currency;
    private String beneficiaryName;
    private String beneficiaryCountry;
    private String purposeCode;
    
    // Merchant information
    private String merchantName;
    private UUID merchantId;
    
    // Timing information
    private LocalDateTime createdAt;
    private LocalDateTime enteredHoldAt;
    private String timeInHold;
    private int priorityScore;
    
    // Violation information
    private List<ViolationInfo> violations;
    private ViolationSeverity highestSeverity;
    private int totalViolations;
    
    // Evidence status
    private boolean evidenceComplete;
    private int missingDocuments;
    
    // Review status
    private boolean hasBeenReviewed;
    private String lastReviewerName;
    private LocalDateTime lastReviewedAt;

    /**
     * Creates queue item from transaction with additional context.
     */
    public static ReviewQueueItemResponse fromTransaction(Transaction transaction) {
        return ReviewQueueItemResponse.builder()
                .transactionId(transaction.getId())
                .referenceNumber(transaction.getReferenceNumber())
                .status(transaction.getStatus())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .beneficiaryName(transaction.getBeneficiaryName())
                .purposeCode(transaction.getPurposeCode())
                .merchantName(transaction.getMerchant().getFullName())
                .merchantId(transaction.getMerchant().getId())
                .createdAt(java.time.LocalDateTime.ofInstant(transaction.getCreatedAt(), java.time.ZoneOffset.UTC))
                .build();
    }

    /**
     * Information about policy violations.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolationInfo {
        private String ruleId;
        private String ruleName;
        private ViolationSeverity severity;
        private String description;
        private String severityDisplayName;

        /**
         * Creates violation info from policy violation entity.
         */
        public static ViolationInfo fromPolicyViolation(com.aegisx.policy.entity.PolicyViolation violation) {
            return ViolationInfo.builder()
                    .ruleId(violation.getRuleId())
                    .ruleName(violation.getRuleName())
                    .severity(violation.getSeverity())
                    .description(violation.getDescription())
                    .severityDisplayName(violation.getSeverity().getDisplayName())
                    .build();
        }
    }

    /**
     * Returns true if this item has critical violations.
     */
    public boolean hasCriticalViolations() {
        return highestSeverity == ViolationSeverity.CRITICAL;
    }

    /**
     * Returns true if this is a high-priority review item.
     */
    public boolean isHighPriority() {
        return priorityScore >= 80 || hasCriticalViolations();
    }

    /**
     * Gets formatted amount string.
     */
    public String getFormattedAmount() {
        return String.format("%s %,.2f", currency, amount);
    }

    /**
     * Gets violation count by severity.
     */
    public long getViolationCountBySeverity(ViolationSeverity severity) {
        return violations == null ? 0 : 
               violations.stream()
                   .filter(v -> v.getSeverity() == severity)
                   .count();
    }

    /**
     * Gets a summary of violations for quick review.
     */
    public String getViolationSummary() {
        if (violations == null || violations.isEmpty()) {
            return "No violations";
        }
        
        long critical = getViolationCountBySeverity(ViolationSeverity.CRITICAL);
        long high = getViolationCountBySeverity(ViolationSeverity.HIGH);
        long medium = getViolationCountBySeverity(ViolationSeverity.MEDIUM);
        long low = getViolationCountBySeverity(ViolationSeverity.LOW);
        
        StringBuilder summary = new StringBuilder();
        if (critical > 0) summary.append(critical).append(" Critical, ");
        if (high > 0) summary.append(high).append(" High, ");
        if (medium > 0) summary.append(medium).append(" Medium, ");
        if (low > 0) summary.append(low).append(" Low");
        
        String result = summary.toString();
        return result.endsWith(", ") ? result.substring(0, result.length() - 2) : result;
    }
}
