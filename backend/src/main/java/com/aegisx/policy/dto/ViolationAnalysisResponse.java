package com.aegisx.policy.dto;

import com.aegisx.policy.entity.ViolationSeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for violation analysis and statistics.
 * Provides aggregated violation data for reporting and analytics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViolationAnalysisResponse {

    private UUID transactionId;
    private String transactionReference;
    private int totalViolations;
    private Map<ViolationSeverity, Long> violationsBySeverity;
    private List<ViolationSummary> violationSummaries;
    private boolean hasBlockingViolations;
    private ViolationSeverity highestSeverity;
    private LocalDateTime firstViolationAt;
    private LocalDateTime lastViolationAt;
    
    // Risk assessment
    private String riskLevel;
    private int riskScore;
    private List<String> recommendedActions;

    /**
     * Summary of violations by rule.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolationSummary {
        private String ruleId;
        private String ruleName;
        private long count;
        private ViolationSeverity highestSeverity;
        private String description;
        private boolean blocksApproval;
    }

    /**
     * Creates analysis response from violation data.
     */
    public static ViolationAnalysisResponse create(UUID transactionId, 
                                                   String transactionReference,
                                                   List<PolicyViolationResponse> violations) {
        ViolationAnalysisResponseBuilder builder = ViolationAnalysisResponse.builder()
                .transactionId(transactionId)
                .transactionReference(transactionReference)
                .totalViolations(violations.size());

        if (violations.isEmpty()) {
            return builder
                    .violationsBySeverity(Map.of())
                    .violationSummaries(List.of())
                    .hasBlockingViolations(false)
                    .riskLevel("LOW")
                    .riskScore(0)
                    .recommendedActions(List.of("No violations detected"))
                    .build();
        }

        // Count violations by severity
        Map<ViolationSeverity, Long> severityCount = violations.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PolicyViolationResponse::getSeverity,
                        java.util.stream.Collectors.counting()));

        // Find highest severity
        ViolationSeverity highestSeverity = violations.stream()
                .map(PolicyViolationResponse::getSeverity)
                .max((s1, s2) -> Integer.compare(s1.getPriorityLevel(), s2.getPriorityLevel()))
                .orElse(ViolationSeverity.LOW);

        // Check for blocking violations
        boolean hasBlocking = violations.stream()
                .anyMatch(PolicyViolationResponse::blocksApproval);

        // Create violation summaries by rule
        List<ViolationSummary> summaries = violations.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PolicyViolationResponse::getRuleId))
                .entrySet().stream()
                .map(entry -> {
                    List<PolicyViolationResponse> ruleViolations = entry.getValue();
                    PolicyViolationResponse first = ruleViolations.get(0);
                    ViolationSeverity ruleHighestSeverity = ruleViolations.stream()
                            .map(PolicyViolationResponse::getSeverity)
                            .max((s1, s2) -> Integer.compare(s1.getPriorityLevel(), s2.getPriorityLevel()))
                            .orElse(ViolationSeverity.LOW);

                    return ViolationSummary.builder()
                            .ruleId(first.getRuleId())
                            .ruleName(first.getRuleName())
                            .count(ruleViolations.size())
                            .highestSeverity(ruleHighestSeverity)
                            .description(first.getDescription())
                            .blocksApproval(ruleHighestSeverity.blocksApproval())
                            .build();
                })
                .toList();

        // Calculate risk assessment
        int riskScore = calculateRiskScore(violations);
        String riskLevel = determineRiskLevel(riskScore, hasBlocking);
        List<String> recommendedActions = generateRecommendedActions(violations, hasBlocking, riskLevel);

        // Get timing information
        LocalDateTime firstViolation = violations.stream()
                .map(PolicyViolationResponse::getCreatedAt)
                .min(LocalDateTime::compareTo)
                .orElse(null);

        LocalDateTime lastViolation = violations.stream()
                .map(PolicyViolationResponse::getCreatedAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return builder
                .violationsBySeverity(severityCount)
                .violationSummaries(summaries)
                .hasBlockingViolations(hasBlocking)
                .highestSeverity(highestSeverity)
                .firstViolationAt(firstViolation)
                .lastViolationAt(lastViolation)
                .riskLevel(riskLevel)
                .riskScore(riskScore)
                .recommendedActions(recommendedActions)
                .build();
    }

    /**
     * Calculates risk score based on violations.
     */
    private static int calculateRiskScore(List<PolicyViolationResponse> violations) {
        return violations.stream()
                .mapToInt(v -> v.getSeverity().getPriorityLevel() * 25)
                .sum();
    }

    /**
     * Determines risk level based on score and blocking violations.
     */
    private static String determineRiskLevel(int riskScore, boolean hasBlocking) {
        if (hasBlocking || riskScore >= 100) {
            return "CRITICAL";
        } else if (riskScore >= 75) {
            return "HIGH";
        } else if (riskScore >= 50) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    /**
     * Generates recommended actions based on violations.
     */
    private static List<String> generateRecommendedActions(List<PolicyViolationResponse> violations, 
                                                          boolean hasBlocking, 
                                                          String riskLevel) {
        List<String> actions = new java.util.ArrayList<>();
        
        if (hasBlocking) {
            actions.add("Manual compliance review required before approval");
        }
        
        long criticalCount = violations.stream()
                .filter(v -> v.getSeverity() == ViolationSeverity.CRITICAL)
                .count();
        
        if (criticalCount > 0) {
            actions.add("Address " + criticalCount + " critical violation(s)");
        }
        
        long highCount = violations.stream()
                .filter(v -> v.getSeverity() == ViolationSeverity.HIGH)
                .count();
        
        if (highCount > 0) {
            actions.add("Review " + highCount + " high-priority violation(s)");
        }
        
        if ("CRITICAL".equals(riskLevel)) {
            actions.add("Escalate to senior compliance officer");
        } else if ("HIGH".equals(riskLevel)) {
            actions.add("Conduct enhanced due diligence");
        }
        
        if (actions.isEmpty()) {
            actions.add("Standard compliance review");
        }
        
        return actions;
    }

    /**
     * Returns true if this analysis indicates high risk.
     */
    public boolean isHighRisk() {
        return "CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel);
    }

    /**
     * Gets formatted risk assessment.
     */
    public String getFormattedRiskAssessment() {
        String icon = switch (riskLevel) {
            case "CRITICAL" -> "🚨";
            case "HIGH" -> "⚠️";
            case "MEDIUM" -> "⚡";
            default -> "✅";
        };
        
        return String.format("%s %s Risk (Score: %d)", icon, riskLevel, riskScore);
    }
}