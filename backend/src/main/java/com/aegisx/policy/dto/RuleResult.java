package com.aegisx.policy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RuleResult represents the outcome of a single rule evaluation.
 * 
 * Purpose:
 * - Record whether a rule passed or failed
 * - Provide human-readable reason and supporting evidence
 * - Enable full auditability and explainability of every decision
 * 
 * Example:
 * {
 *   "rule": "DOCUMENT_COMPLETENESS",
 *   "passed": true,
 *   "reason": "All required documents present",
 *   "evidence": "Invoice: present, Shipping Proof: present, Export Declaration: present"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleResult {
    
    @JsonProperty("rule")
    private String rule;
    
    @JsonProperty("passed")
    private Boolean passed;
    
    @JsonProperty("reason")
    private String reason;
    
    @JsonProperty("evidence")
    private String evidence;
}
