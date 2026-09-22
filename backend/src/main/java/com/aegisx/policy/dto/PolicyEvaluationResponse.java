package com.aegisx.policy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PolicyEvaluationResponse represents the API response for policy evaluation results.
 * 
 * Purpose:
 * - Return the final compliance decision to client
 * - Include full rule audit trail and evidence
 * - Enable merchants and compliance officers to understand the decision
 * - Provide performance metrics (processing_duration_ms) for monitoring
 * 
 * Example:
 * {
 *   "id": "uuid",
 *   "transactionId": "uuid",
 *   "decision": "APPROVED",
 *   "overallScore": 95.0,
 *   "rules": [
 *     {
 *       "rule": "DOCUMENT_COMPLETENESS",
 *       "passed": true,
 *       "reason": "All required documents present",
 *       "evidence": "Invoice: present, Shipping: present, Export: present"
 *     }
 *   ],
 *   "processingDurationMs": 245,
 *   "createdAt": "2026-09-22T14:30:45"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEvaluationResponse {
    
    @JsonProperty("id")
    private UUID id;
    
    @JsonProperty("transactionId")
    private UUID transactionId;
    
    @JsonProperty("decision")
    private String decision;
    
    @JsonProperty("overallScore")
    private BigDecimal overallScore;
    
    @JsonProperty("rules")
    private List<RuleResult> rules;
    
    @JsonProperty("processingDurationMs")
    private Long processingDurationMs;
    
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
