package com.aegisx.policy.dto;

import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.ViolationSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for Policy Violation information.
 * Used in REST API responses to provide violation details to clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyViolationResponse {

    private UUID id;
    private UUID policyEvaluationId;
    private UUID transactionId;
    private String transactionReference;
    private String ruleId;
    private String ruleName;
    private ViolationSeverity severity;
    private String severityDisplayName;
    private String description;
    private JsonNode ruleContext;
    private LocalDateTime createdAt;
    
    // Additional display information
    private boolean isCritical;
    private boolean requiresReview;
    private String severityColor;

    /**
     * Creates response DTO from entity.
     */
    public static PolicyViolationResponse fromEntity(PolicyViolation violation) {
        PolicyViolationResponseBuilder builder = PolicyViolationResponse.builder()
                .id(violation.getId())
                .policyEvaluationId(violation.getPolicyEvaluation().getId())
                .transactionId(violation.getPolicyEvaluation().getTransaction().getId())
                .transactionReference(violation.getPolicyEvaluation().getTransaction().getReferenceNumber())
                .ruleId(violation.getRuleId())
                .ruleName(violation.getRuleName())
                .severity(violation.getSeverity())
                .severityDisplayName(violation.getSeverity().getDisplayName())
                .description(violation.getDescription())
                .ruleContext(violation.getRuleContext())
                .createdAt(java.time.LocalDateTime.ofInstant(violation.getCreatedAt(), java.time.ZoneOffset.UTC))
                .isCritical(violation.isCritical())
                .requiresReview(violation.getSeverity().requiresReview())
                .severityColor(getSeverityColor(violation.getSeverity()));

        return builder.build();
    }

    /**
     * Gets color code for severity level for UI display.
     */
    private static String getSeverityColor(ViolationSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "#DC2626"; // Red
            case HIGH -> "#EA580C";     // Orange
            case MEDIUM -> "#D97706";   // Amber
            case LOW -> "#059669";      // Green
        };
    }

    /**
     * Returns true if this violation blocks automatic approval.
     */
    public boolean blocksApproval() {
        return severity.blocksApproval();
    }

    /**
     * Gets formatted severity display with icon.
     */
    public String getFormattedSeverity() {
        String icon = switch (severity) {
            case CRITICAL -> "🚨";
            case HIGH -> "⚠️";
            case MEDIUM -> "⚡";
            case LOW -> "ℹ️";
        };
        return icon + " " + severityDisplayName;
    }

    /**
     * Gets truncated description for list views.
     */
    public String getTruncatedDescription(int maxLength) {
        if (description == null) {
            return "";
        }
        
        if (description.length() <= maxLength) {
            return description;
        }
        
        return description.substring(0, maxLength) + "...";
    }

    /**
     * Gets rule display name with ID.
     */
    public String getRuleDisplayName() {
        return String.format("[%s] %s", ruleId, ruleName);
    }
}
