package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.domain.RecommendationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Module G: a recommended next action for an exception.
 *
 * <p>{@code category} is the safety gate. Only {@code READ_ONLY} recommendations may be executed
 * without a human. {@code FINANCIAL} recommendations are never executed by this prototype unless
 * financial execution is explicitly enabled by configuration — and even then, only after the
 * provider's current state has been re-read.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_recovery_recommendations")
public class RecoveryRecommendation extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "exception_id", nullable = false)
    private UUID exceptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation_type", nullable = false, length = 40)
    private RecommendationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private RecommendationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RecommendationStatus status;

    @Column(name = "rationale", nullable = false, length = 500)
    private String rationale;

    /** Why acting here could be unsafe: shown verbatim to the operator. */
    @Column(name = "risk_note", length = 500)
    private String riskNote;

    @Column(name = "requires_human_approval", nullable = false)
    private boolean requiresHumanApproval;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "execution_outcome", length = 500)
    private String executionOutcome;

    @Column(name = "executed_by")
    private UUID executedBy;
}
