package com.aegisx.policy.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Policy Evaluation Entity - immutable record of compliance policy decisions.
 * Implements the policy evaluations specification from TAS Section 6.
 * 
 * Purpose:
 * - Persist final system decision: APPROVED or HOLD
 * - Store complete rule evaluation audit trail with violations
 * - Enable reproducibility and explainability of compliance decisions
 * - Immutable: only insert operations allowed, no updates
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "policy_evaluations")
public class PolicyEvaluation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_analysis_id")
    private AIAnalysis aiAnalysis;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 20)
    private PolicyVerdict verdict;

    @Column(name = "total_rules_count", nullable = false)
    private Integer totalRulesCount;

    @Column(name = "passed_rules_count", nullable = false)
    private Integer passedRulesCount;

    @Column(name = "failed_rules_count", nullable = false)
    private Integer failedRulesCount;

    @Column(name = "engine_version", nullable = false, length = 20)
    @Builder.Default
    private String engineVersion = "1.0.0";

    @Column(name = "evaluated_at", nullable = false)
    private java.time.LocalDateTime evaluatedAt;

    @OneToMany(mappedBy = "policyEvaluation", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PolicyViolation> violations = new ArrayList<>();

    // Legacy fields for backward compatibility
    @Column(name = "final_decision", length = 50)
    private String finalDecision;

    @Column(name = "overall_score")
    private java.math.BigDecimal overallScore;

    @Type(JsonType.class)
    @Column(name = "evaluation_json", columnDefinition = "jsonb")
    private JsonNode evaluationJson;

    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;

    /**
     * Adds a policy violation to this evaluation.
     */
    public void addViolation(PolicyViolation violation) {
        if (violations == null) {
            violations = new ArrayList<>();
        }
        violations.add(violation);
        violation.setPolicyEvaluation(this);
    }

    /**
     * Creates a new policy evaluation with the given verdict.
     */
    public static PolicyEvaluation createEvaluation(Transaction transaction, AIAnalysis aiAnalysis, 
                                                    PolicyVerdict verdict, int totalRules, 
                                                    int passedRules, int failedRules) {
        return PolicyEvaluation.builder()
                .transaction(transaction)
                .aiAnalysis(aiAnalysis)
                .verdict(verdict)
                .totalRulesCount(totalRules)
                .passedRulesCount(passedRules)
                .failedRulesCount(failedRules)
                .evaluatedAt(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * Returns true if this evaluation resulted in approval.
     */
    public boolean isApproved() {
        return verdict == PolicyVerdict.APPROVED;
    }

    /**
     * Returns true if this evaluation resulted in a hold.
     */
    public boolean isHold() {
        return verdict == PolicyVerdict.HOLD;
    }

    /**
     * Gets all critical violations.
     */
    public List<PolicyViolation> getCriticalViolations() {
        return violations.stream()
                .filter(PolicyViolation::isCritical)
                .toList();
    }

    /**
     * Returns true if there are any critical violations.
     */
    public boolean hasCriticalViolations() {
        return violations.stream().anyMatch(PolicyViolation::isCritical);
    }
}
