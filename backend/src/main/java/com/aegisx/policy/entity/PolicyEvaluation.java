package com.aegisx.policy.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PolicyEvaluation entity represents an immutable record of compliance policy decisions.
 * 
 * Purpose:
 * - Persist the final system decision: APPROVED or COMPLIANCE_HOLD
 * - Store complete rule evaluation audit trail
 * - Enable reproducibility and explainability of every compliance decision
 * - Never overwrite: each evaluation is immutable; only insert operations allowed
 * 
 * Constraints:
 * - final_decision must be APPROVED or COMPLIANCE_HOLD
 * - overall_score reflects aggregated rule results (0-100 or null)
 * - evaluation_json contains full rule results and evidence
 * - processing_duration_ms tracks performance for monitoring
 * - Transaction must exist; ai_analysis may be null but typically populated
 */
@Entity
@Table(name = "policy_evaluations", indexes = {
    @Index(name = "idx_policy_eval_transaction_id", columnList = "transaction_id"),
    @Index(name = "idx_policy_eval_created_at", columnList = "created_at DESC"),
    @Index(name = "idx_policy_eval_final_decision", columnList = "final_decision")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEvaluation {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    
    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;
    
    @Column(name = "final_decision", nullable = false, length = 50)
    private String finalDecision;
    
    @Column(name = "overall_score")
    private BigDecimal overallScore;
    
    @Column(name = "evaluation_json", nullable = false, columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode evaluationJson;
    
    @Column(name = "processing_duration_ms", nullable = false)
    private Long processingDurationMs;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
