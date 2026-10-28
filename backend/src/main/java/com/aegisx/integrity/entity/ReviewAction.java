package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.ReviewActionType;
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

import java.util.UUID;

/**
 * Module F/H: a persisted human decision.
 *
 * <p>Each row captures the rationale, the evidence the operator cited, and the resulting exception
 * status, so the decision record explains what was known at the time it was taken.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_review_actions")
public class ReviewAction extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "exception_id", nullable = false)
    private UUID exceptionId;

    @Column(name = "recommendation_id")
    private UUID recommendationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private ReviewActionType actionType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role", nullable = false, length = 40)
    private String actorRole;

    @Column(name = "rationale", nullable = false, length = 1000)
    private String rationale;

    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_exception_status", length = 20)
    private ExceptionStatus resultingExceptionStatus;
}
