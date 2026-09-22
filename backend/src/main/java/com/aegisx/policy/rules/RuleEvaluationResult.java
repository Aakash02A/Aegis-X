package com.aegisx.policy.rules;

import com.aegisx.policy.dto.RuleResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RuleEvaluationResult encapsulates the outcome of a single rule evaluation.
 * 
 * Purpose:
 * - Record whether rule passed or failed
 * - Provide human-readable reason for the result
 * - Include specific evidence supporting the decision
 * - Enable full auditability and explainability
 * 
 * Design:
 * - Immutable once created
 * - Converts to RuleResult DTO for API responses
 * - All evidence is text-based for clarity and searchability
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleEvaluationResult {
    
    private String ruleName;
    private Boolean passed;
    private String reason;
    private String evidence;
    
    /**
     * Convert to RuleResult DTO for API response.
     */
    public RuleResult toDTO() {
        return RuleResult.builder()
            .rule(ruleName)
            .passed(passed)
            .reason(reason)
            .evidence(evidence)
            .build();
    }
}
