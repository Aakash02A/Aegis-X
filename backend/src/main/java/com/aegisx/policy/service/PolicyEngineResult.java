package com.aegisx.policy.service;

import com.aegisx.policy.rules.RuleEvaluationResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PolicyEngineResult encapsulates the output of RuleEngine evaluation.
 * 
 * Purpose:
 * - Carry final decision through service layers
 * - Include all rule results for auditability
 * - Record processing duration for monitoring
 * - Convert to JSON for database persistence
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEngineResult {
    
    private String finalDecision;
    private List<RuleEvaluationResult> ruleResults;
    private Long processingDurationMs;
}
