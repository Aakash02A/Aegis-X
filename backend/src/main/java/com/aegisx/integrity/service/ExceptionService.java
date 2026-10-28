package com.aegisx.integrity.service;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.ReviewActionType;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.ProviderObservation;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.entity.ReconciliationFinding;
import com.aegisx.integrity.entity.ReviewAction;
import com.aegisx.integrity.entity.StateTransitionRecord;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.repository.ExceptionCaseRepository;
import com.aegisx.integrity.repository.ProviderObservationRepository;
import com.aegisx.integrity.repository.RecoveryRecommendationRepository;
import com.aegisx.integrity.repository.ReconciliationFindingRepository;
import com.aegisx.integrity.repository.ReviewActionRepository;
import com.aegisx.integrity.repository.StateTransitionRepository;
import com.aegisx.integrity.security.ActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module F/H: exception queue, investigation detail and the persisted human decision workflow.
 *
 * <p>Every decision requires a rationale, and every decision records the evidence that was
 * available at the time. A resolution is provisional: a later reconciliation run that still
 * observes the discrepancy reopens the case automatically.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionService {

    private static final List<ExceptionStatus> OPEN_STATUSES = List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW);

    private final ExceptionCaseRepository exceptionRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final RecoveryRecommendationRepository recommendationRepository;
    private final ReviewActionRepository reviewActionRepository;
    private final ProviderObservationRepository observationRepository;
    private final StateTransitionRepository transitionRepository;
    private final IntegrityAuditService auditService;

    public record ExceptionDetail(ExceptionCase exceptionCase,
                                  List<ReconciliationFinding> findings,
                                  List<RecoveryRecommendation> recommendations,
                                  List<ReviewAction> reviewActions,
                                  List<ProviderObservation> observations,
                                  List<StateTransitionRecord> transitions,
                                  boolean detectedInLatestRun) { }

    @Transactional(readOnly = true)
    public Page<ExceptionCase> queue(UUID workspaceId, List<ExceptionStatus> statuses, Pageable pageable) {
        List<ExceptionStatus> effective = (statuses == null || statuses.isEmpty()) ? OPEN_STATUSES : statuses;
        return exceptionRepository.findByWorkspaceIdAndStatusInOrderBySeverityAscCreatedAtAsc(workspaceId, effective, pageable);
    }

    @Transactional(readOnly = true)
    public List<ExceptionCase> list(UUID workspaceId) {
        return exceptionRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public ExceptionDetail detail(UUID workspaceId, UUID exceptionId) {
        ExceptionCase exceptionCase = require(workspaceId, exceptionId);
        List<ReconciliationFinding> findings = findingRepository.findByExceptionIdOrderByCreatedAtDesc(exceptionId);
        List<UUID> entityIds = entityIdsOf(exceptionCase);
        List<ProviderObservation> observations = observationRepository
                .findAllByWorkspaceIdOrderByObservedAtDesc(workspaceId).stream()
                .filter(observation -> touches(observation, entityIds))
                .toList();
        List<StateTransitionRecord> transitions = transitionRepository
                .findAllByWorkspaceIdOrderByOccurredAtDesc(workspaceId).stream()
                .filter(transition -> entityIds.contains(transition.getEntityId()))
                .toList();
        boolean detectedInLatestRun = !findings.isEmpty()
                && exceptionCase.getLastSeenRunId() != null
                && findings.get(0).getRunId().equals(exceptionCase.getLastSeenRunId());
        return new ExceptionDetail(exceptionCase, findings,
                recommendationRepository.findByExceptionIdOrderByCreatedAtAsc(exceptionId),
                reviewActionRepository.findByExceptionIdOrderByCreatedAtAsc(exceptionId),
                observations, transitions, detectedInLatestRun);
    }

    @Transactional
    public ExceptionCase openCase(UUID workspaceId, UUID exceptionId, ActorContext.Actor actor, String note) {
        ExceptionCase exceptionCase = require(workspaceId, exceptionId);
        if (exceptionCase.getStatus() == ExceptionStatus.RESOLVED) {
            throw new IntegrityConflictException("A resolved exception must be reopened by reconciliation, not manually");
        }
        boolean alreadyInReview = exceptionCase.getStatus() == ExceptionStatus.IN_REVIEW;
        exceptionCase.setStatus(ExceptionStatus.IN_REVIEW);
        exceptionCase.setAssignedTo(actor.userId());
        exceptionRepository.save(exceptionCase);

        record(workspaceId, exceptionCase, null, ReviewActionType.OPEN_CASE, actor,
                note == null || note.isBlank() ? "Operator opened the case for investigation" : note,
                null, exceptionCase.getStatus());
        if (!alreadyInReview) {
            audit(workspaceId, "EXCEPTION_OPENED_FOR_REVIEW", exceptionCase, actor);
        }
        return exceptionCase;
    }

    @Transactional
    public ReviewAction addNote(UUID workspaceId, UUID exceptionId, ActorContext.Actor actor, String note) {
        if (note == null || note.isBlank()) {
            throw new IntegrityConflictException("A note cannot be empty");
        }
        ExceptionCase exceptionCase = require(workspaceId, exceptionId);
        return record(workspaceId, exceptionCase, null, ReviewActionType.ADD_NOTE, actor, note, null,
                exceptionCase.getStatus());
    }

    @Transactional
    public ExceptionCase resolve(UUID workspaceId, UUID exceptionId, ActorContext.Actor actor,
                                String summary, String evidence) {
        if (summary == null || summary.isBlank()) {
            throw new IntegrityConflictException("A resolution requires a written summary of what was done");
        }
        ExceptionCase exceptionCase = require(workspaceId, exceptionId);
        if (exceptionCase.getStatus() == ExceptionStatus.DISMISSED) {
            throw new IntegrityConflictException("A dismissed exception cannot be resolved; reopen it first");
        }
        exceptionCase.setStatus(ExceptionStatus.RESOLVED);
        exceptionCase.setResolutionSummary(summary);
        exceptionCase.setResolutionEvidenceJson(evidence);
        exceptionCase.setResolvedAt(Instant.now());
        exceptionRepository.save(exceptionCase);

        record(workspaceId, exceptionCase, null, ReviewActionType.RECORD_RESOLUTION, actor, summary, evidence,
                ExceptionStatus.RESOLVED);
        audit(workspaceId, "EXCEPTION_RESOLVED", exceptionCase, actor);
        log.info("Exception {} resolved by {}; a later reconciliation run will verify the resolution",
                exceptionId, actor.label());
        return exceptionCase;
    }

    @Transactional
    public ExceptionCase dismiss(UUID workspaceId, UUID exceptionId, ActorContext.Actor actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IntegrityConflictException("Dismissing an exception requires a reason");
        }
        ExceptionCase exceptionCase = require(workspaceId, exceptionId);
        exceptionCase.setStatus(ExceptionStatus.DISMISSED);
        exceptionCase.setResolutionSummary("Dismissed: " + reason);
        exceptionCase.setResolvedAt(Instant.now());
        exceptionRepository.save(exceptionCase);

        record(workspaceId, exceptionCase, null, ReviewActionType.DISMISS, actor, reason, null,
                ExceptionStatus.DISMISSED);
        audit(workspaceId, "EXCEPTION_DISMISSED", exceptionCase, actor);
        return exceptionCase;
    }

    @Transactional(readOnly = true)
    public ExceptionCase require(UUID workspaceId, UUID exceptionId) {
        return exceptionRepository.findById(exceptionId)
                .filter(candidate -> candidate.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new IntegrityNotFoundException("Exception " + exceptionId + " does not exist in this workspace"));
    }

    /** Records a review action. Exposed so the recovery service can record approvals on the case timeline. */
    @Transactional
    public ReviewAction record(UUID workspaceId, ExceptionCase exceptionCase, UUID recommendationId,
                               ReviewActionType type, ActorContext.Actor actor, String rationale,
                               String evidence, ExceptionStatus resultingStatus) {
        ReviewAction action = reviewActionRepository.save(ReviewAction.builder()
                .workspaceId(workspaceId)
                .exceptionId(exceptionCase.getId())
                .recommendationId(recommendationId)
                .actionType(type)
                .actorId(actor.userId())
                .actorRole(actor.role())
                .rationale(rationale)
                .evidenceJson(evidence)
                .resultingExceptionStatus(resultingStatus)
                .build());
        return action;
    }

    private void audit(UUID workspaceId, String eventType, ExceptionCase exceptionCase, ActorContext.Actor actor) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exceptionId", exceptionCase.getId().toString());
        detail.put("findingType", exceptionCase.getFindingType().name());
        detail.put("status", exceptionCase.getStatus().name());
        auditService.record(eventType, "EXCEPTION", actor.label(), workspaceId, detail,
                exceptionCase.getOrderId(), exceptionCase.getPaymentId(), exceptionCase.getRefundId(),
                exceptionCase.getTransferId(), exceptionCase.getId(), exceptionCase.getLastSeenRunId(),
                actor.userId(), false);
    }

    private static List<UUID> entityIdsOf(ExceptionCase exceptionCase) {
        List<UUID> ids = new ArrayList<>();
        if (exceptionCase.getOrderId() != null) {
            ids.add(exceptionCase.getOrderId());
        }
        if (exceptionCase.getPaymentId() != null) {
            ids.add(exceptionCase.getPaymentId());
        }
        if (exceptionCase.getRefundId() != null) {
            ids.add(exceptionCase.getRefundId());
        }
        if (exceptionCase.getTransferId() != null) {
            ids.add(exceptionCase.getTransferId());
        }
        return ids;
    }

    private static boolean touches(ProviderObservation observation, List<UUID> entityIds) {
        return (observation.getOrderId() != null && entityIds.contains(observation.getOrderId()))
                || (observation.getPaymentId() != null && entityIds.contains(observation.getPaymentId()))
                || (observation.getRefundId() != null && entityIds.contains(observation.getRefundId()))
                || (observation.getTransferId() != null && entityIds.contains(observation.getTransferId()));
    }
}
