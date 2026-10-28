package com.aegisx.policy.entity;

import com.aegisx.common.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Policy Violation Entity - tracks detailed rule failures during policy evaluation.
 * Implements the policy violations specification from TAS Section 6.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "policy_violations")
public class PolicyViolation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_evaluation_id", nullable = false)
    private PolicyEvaluation policyEvaluation;

    @Column(name = "rule_id", nullable = false, length = 20)
    private String ruleId;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ViolationSeverity severity;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_context")
    private JsonNode ruleContext;

    /**
     * Creates a policy violation for a specific rule failure.
     */
    public static PolicyViolation createViolation(PolicyEvaluation evaluation, String ruleId, 
                                                   String ruleName, ViolationSeverity severity, 
                                                   String description, JsonNode context) {
        PolicyViolation violation = PolicyViolation.builder()
                .policyEvaluation(evaluation)
                .ruleId(ruleId)
                .ruleName(ruleName)
                .severity(severity)
                .description(description)
                .ruleContext(context)
                .build();
        java.time.Instant now = java.time.Instant.now();
        violation.setCreatedAt(now);
        violation.setUpdatedAt(now);
        return violation;
    }

    /**
     * Returns true if this is a critical violation that must be addressed.
     */
    public boolean isCritical() {
        return severity == ViolationSeverity.CRITICAL;
    }

    /**
     * Returns true if this is a high-priority violation.
     */
    public boolean isHighPriority() {
        return severity == ViolationSeverity.HIGH || severity == ViolationSeverity.CRITICAL;
    }

    /**
     * Gets a formatted display string for this violation.
     */
    public String getDisplayString() {
        return String.format("[%s] %s: %s", ruleId, ruleName, description);
    }
}
