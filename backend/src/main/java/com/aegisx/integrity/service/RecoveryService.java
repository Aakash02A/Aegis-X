package com.aegisx.integrity.service;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.domain.ReviewActionType;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.FinancialExecutionDisabledException;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.repository.RecoveryRecommendationRepository;
import com.aegisx.integrity.security.ActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module G: recovery execution, guarded by category.
 *
 * <p>What this service will and will not do:</p>
 * <ul>
 *   <li><b>READ_ONLY</b> recommendations may be executed. Execution means re-reading the provider's
 *       current state through the adapter and recording the result as a fresh ranked observation —
 *       never a financial operation. It is idempotent by construction because it changes no
 *       provider state.</li>
 *   <li><b>HUMAN_APPROVAL_REQUIRED</b> recommendations can be approved or rejected, which only
 *       records a decision.</li>
 *   <li><b>FINANCIAL</b> recommendations can never be executed by this service. Approval is refused
 *       unless financial execution is explicitly enabled in configuration, and even then the
 *       approval records that execution happens outside the engine after a provider state re-read.
 *       This is what makes the "no unsafe automatic action" property structural rather than a
 *       promise, and it is asserted by tests.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private static final String TIMEOUT_GUIDANCE =
            "Never repeat a provider request merely because a previous attempt timed out: it may already have "
                    + "succeeded. Read the provider's current state first and use the provider's documented "
                    + "idempotency mechanism where one exists.";

    private final RecoveryRecommendationRepository recommendationRepository;
    private final ExceptionService exceptionService;
    private final ProviderSyncService providerSyncService;
    private final WorkspaceService workspaceService;
    private final IntegrityProperties properties;
    private final IntegrityAuditService auditService;

    public record ExecutionOutcome(UUID recommendationId, RecommendationStatus status, String detail) { }

    @Transactional(readOnly = true)
    public List<RecoveryRecommendation> list(UUID workspaceId) {
        return recommendationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<RecoveryRecommendation> forException(UUID workspaceId, UUID exceptionId) {
        exceptionService.require(workspaceId, exceptionId);
        return recommendationRepository.findByExceptionIdOrderByCreatedAtAsc(exceptionId);
    }

    @Transactional
    public RecoveryRecommendation approve(UUID workspaceId, UUID recommendationId,
                                          ActorContext.Actor actor, String rationale) {
        RecoveryRecommendation recommendation = require(workspaceId, recommendationId);
        ensureProposed(recommendation);

        if (recommendation.getCategory() == RecommendationCategory.FINANCIAL) {
            if (!properties.isAllowFinancialExecution()) {
                throw new FinancialExecutionDisabledException(
                        "Financial execution is disabled in this prototype. The recommendation stays proposed so no "
                                + "money can move without an operator acting outside the engine. " + TIMEOUT_GUIDANCE);
            }
            recommendation.setStatus(RecommendationStatus.EXECUTED);
            recommendation.setExecutionOutcome("Approved and recorded. Aegis-X does not perform the provider call; "
                    + "an operator must execute it and then re-run reconciliation. " + TIMEOUT_GUIDANCE);
            recommendation.setExecutedAt(Instant.now());
            recommendation.setExecutedBy(actor.userId());
            recommendationRepository.save(recommendation);
            recordDecision(workspaceId, recommendation, actor, ReviewActionType.APPROVE_RECOMMENDATION, rationale);
            return recommendation;
        }

        recommendation.setStatus(RecommendationStatus.APPROVED);
        recommendation.setExecutionOutcome("Approved by " + actor.label() + " for manual follow-up.");
        recommendationRepository.save(recommendation);
        recordDecision(workspaceId, recommendation, actor, ReviewActionType.APPROVE_RECOMMENDATION, rationale);
        return recommendation;
    }

    @Transactional
    public RecoveryRecommendation reject(UUID workspaceId, UUID recommendationId,
                                         ActorContext.Actor actor, String rationale) {
        RecoveryRecommendation recommendation = require(workspaceId, recommendationId);
        ensureProposed(recommendation);
        recommendation.setStatus(RecommendationStatus.REJECTED);
        recommendation.setExecutionOutcome("Rejected by " + actor.label());
        recommendationRepository.save(recommendation);
        recordDecision(workspaceId, recommendation, actor, ReviewActionType.REJECT_RECOMMENDATION, rationale);
        return recommendation;
    }

    @Transactional
    public ExecutionOutcome executeReadOnly(UUID workspaceId, UUID recommendationId, ActorContext.Actor actor) {
        RecoveryRecommendation recommendation = require(workspaceId, recommendationId);
        if (recommendation.getCategory() != RecommendationCategory.READ_ONLY) {
            throw new FinancialExecutionDisabledException(
                    "Only READ_ONLY recommendations may be executed automatically. "
                            + recommendation.getType() + " is " + recommendation.getCategory() + ". " + TIMEOUT_GUIDANCE);
        }
        if (recommendation.getStatus() == RecommendationStatus.EXECUTED) {
            throw new IntegrityConflictException("This read-only recommendation has already been executed");
        }

        Workspace workspace = workspaceService.get(workspaceId);
        ProviderSyncService.SyncSummary summary = providerSyncService.sync(workspace);
        String detail = summary.providerReachable()
                ? "Executed: provider state re-read (" + summary.ordersSynced() + " orders, "
                + summary.paymentsSynced() + " payments, " + summary.refundsSynced() + " refunds, "
                + summary.transfersSynced() + " transfers refreshed). Re-run reconciliation to verify."
                : "Not executed: the provider is unreachable, so a state re-read is impossible right now. "
                + "Retry when connectivity is restored.";

        recommendation.setStatus(summary.providerReachable()
                ? RecommendationStatus.EXECUTED
                : RecommendationStatus.FAILED);
        recommendation.setExecutionOutcome(detail);
        recommendation.setExecutedAt(Instant.now());
        recommendation.setExecutedBy(actor.userId());
        recommendationRepository.save(recommendation);
        recordDecision(workspaceId, recommendation, actor,
                ReviewActionType.EXECUTE_READ_ONLY_RECOMMENDATION, detail);

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("recommendationId", recommendationId.toString());
        auditDetail.put("type", recommendation.getType().name());
        auditDetail.put("category", recommendation.getCategory().name());
        auditDetail.put("outcome", detail);
        auditService.record("RECOVERY_RECOMMENDATION_EXECUTED", "RECOVERY", actor.label(), workspaceId,
                auditDetail, null, null, null, null, recommendation.getExceptionId(), null,
                actor.userId(), workspace.getProviderMode().isSimulated());

        return new ExecutionOutcome(recommendationId, recommendation.getStatus(), detail);
    }

    @Transactional(readOnly = true)
    public RecoveryRecommendation require(UUID workspaceId, UUID recommendationId) {
        return recommendationRepository.findById(recommendationId)
                .filter(candidate -> candidate.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new IntegrityNotFoundException(
                        "Recommendation " + recommendationId + " does not exist in this workspace"));
    }

    private void ensureProposed(RecoveryRecommendation recommendation) {
        if (recommendation.getStatus() != RecommendationStatus.PROPOSED) {
            throw new IntegrityConflictException("Only a proposed recommendation can be decided; current status is "
                    + recommendation.getStatus());
        }
    }

    private void recordDecision(UUID workspaceId, RecoveryRecommendation recommendation,
                               ActorContext.Actor actor, ReviewActionType type, String rationale) {
        ExceptionCase exceptionCase = exceptionService.require(workspaceId, recommendation.getExceptionId());
        exceptionService.record(workspaceId, exceptionCase, recommendation.getId(), type, actor,
                rationale == null || rationale.isBlank() ? type.name() : rationale, null, exceptionCase.getStatus());
    }
}
