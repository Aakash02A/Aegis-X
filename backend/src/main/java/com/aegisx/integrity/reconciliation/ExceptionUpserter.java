package com.aegisx.integrity.reconciliation;

import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.domain.ReviewActionType;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.ReconciliationRun;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.entity.ReviewAction;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.recovery.RecommendationPlan;
import com.aegisx.integrity.recovery.RecommendationPolicy;
import com.aegisx.integrity.repository.ExceptionCaseRepository;
import com.aegisx.integrity.repository.RecoveryRecommendationRepository;
import com.aegisx.integrity.repository.ReviewActionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a {@link FindingCandidate} into a persisted {@link ExceptionCase}, and keeps it honest.
 *
 * <p>Extracted from the reconciliation service so that the payment dimension and the evidence
 * dimension share <em>one</em> case lifecycle. Two implementations would eventually disagree about
 * deduplication or reopening, and an operator would then see two queues with different rules — which
 * is exactly the outcome the product brief warns against.</p>
 *
 * <p>Three behaviours are load-bearing and are asserted by tests rather than assumed:</p>
 * <ul>
 *   <li><strong>Dedupe.</strong> An exception is identified by {@code (workspaceId, dedupeKey)}.
 *       Seeing the same discrepancy again refreshes the case and increments its detection count
 *       instead of opening a second one.</li>
 *   <li><strong>Auto-reopen.</strong> A resolution is provisional. If a later assessment still
 *       observes the discrepancy, the case returns to {@code OPEN} with an {@code AUTO_REOPEN}
 *       review action explaining why the recorded resolution could not be verified.</li>
 *   <li><strong>Recommendations are idempotent.</strong> A recommendation is added at most once per
 *       (case, type), so repeated runs do not pile up duplicates for an operator to dismiss.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionUpserter {

    private final ExceptionCaseRepository exceptionRepository;
    private final RecoveryRecommendationRepository recommendationRepository;
    private final ReviewActionRepository reviewActionRepository;
    private final RecommendationPolicy recommendationPolicy;
    private final ObjectMapper objectMapper;

    /** What happened to an exception case during one pass. */
    public record Upsert(ExceptionCase exception, boolean created, boolean reopened) { }

    public Upsert upsert(UUID workspaceId, ReconciliationRun run, FindingCandidate candidate,
                         UUID triggeredBy) {
        Optional<ExceptionCase> existing =
                exceptionRepository.findByWorkspaceIdAndDedupeKey(workspaceId, candidate.dedupeKey());
        UUID runId = run == null ? null : run.getId();
        boolean providerReachable = run == null || run.isProviderReachable();

        if (existing.isEmpty()) {
            ExceptionCase created = exceptionRepository.save(ExceptionCase.builder()
                    .workspaceId(workspaceId)
                    .dedupeKey(candidate.dedupeKey())
                    .findingType(candidate.type())
                    .severity(candidate.severity())
                    .status(ExceptionStatus.OPEN)
                    .certainty(candidate.certainty())
                    .orderId(candidate.orderId())
                    .paymentId(candidate.paymentId())
                    .refundId(candidate.refundId())
                    .transferId(candidate.transferId())
                    .providerResourceRef(candidate.entityKey())
                    .title(truncate(titleFor(candidate), 195))
                    .diagnosis(truncate(candidate.summary(), 990))
                    .supportingEvidenceJson(toJson(candidate.evidence()))
                    .missingEvidenceJson(toJson(candidate.missingEvidence()))
                    .recommendedAction(truncate(
                            recommendationPolicy.primary(candidate.type(), null).rationale(), 495))
                    .firstDetectedRunId(runId)
                    .lastSeenRunId(runId)
                    .detectionCount(1)
                    .providerUnreachable(!providerReachable)
                    .build());
            return new Upsert(created, true, false);
        }

        ExceptionCase exception = existing.get();
        boolean reopen = exception.getStatus() == ExceptionStatus.RESOLVED
                || exception.getStatus() == ExceptionStatus.DISMISSED;
        if (reopen) {
            exception.setStatus(ExceptionStatus.OPEN);
            exception.setResolvedAt(null);
            exception.setResolutionSummary(null);
            exception.setResolutionEvidenceJson(null);
            reviewActionRepository.save(ReviewAction.builder()
                    .workspaceId(workspaceId)
                    .exceptionId(exception.getId())
                    .actionType(ReviewActionType.AUTO_REOPEN)
                    .actorId(triggeredBy)
                    .actorRole("SYSTEM")
                    .rationale(runId == null
                            ? "A later evidence assessment still observed this discrepancy, so the recorded "
                                    + "resolution could not be verified"
                            : "Reconciliation run " + runId
                                    + " still observed this discrepancy, so the recorded resolution could not be "
                                    + "verified")
                    .resultingExceptionStatus(ExceptionStatus.OPEN)
                    .build());
        }
        exception.setSeverity(candidate.severity());
        exception.setCertainty(candidate.certainty());
        exception.setDiagnosis(truncate(candidate.summary(), 990));
        exception.setSupportingEvidenceJson(toJson(candidate.evidence()));
        exception.setMissingEvidenceJson(toJson(candidate.missingEvidence()));
        exception.setLastSeenRunId(runId);
        exception.setDetectionCount(exception.getDetectionCount() + 1);
        exception.setProviderUnreachable(!providerReachable);
        return new Upsert(exceptionRepository.save(exception), false, reopen);
    }

    /**
     * Attaches every recommendation the policy justifies for this finding.
     *
     * @param pack the evidence pack of the current pass, or {@code null} when the finding came from
     *             the evidence dimension. Only the secondary policy consults it, and only for refund
     *             and transfer discrepancies, so a null pack is safe for every other finding type.
     */
    public void attachRecommendations(UUID workspaceId, ExceptionCase exception,
                                      FindingCandidate candidate, EvidencePack pack) {
        if (candidate.type().isEvidenceFinding() && pack != null) {
            log.debug("Evidence finding {} does not consult the payment evidence pack", candidate.type());
        }
        for (RecommendationPlan plan : recommendationPolicy.planFor(candidate.type(), pack)) {
            if (recommendationRepository.existsByExceptionIdAndType(exception.getId(), plan.type())) {
                continue;
            }
            recommendationRepository.save(RecoveryRecommendation.builder()
                    .workspaceId(workspaceId)
                    .exceptionId(exception.getId())
                    .type(plan.type())
                    .category(plan.category())
                    .status(RecommendationStatus.PROPOSED)
                    .rationale(truncate(plan.rationale(), 495))
                    .riskNote(truncate(plan.riskNote(), 495))
                    .requiresHumanApproval(plan.requiresHumanApproval())
                    .build());
        }
    }

    /**
     * Operator-facing title for a finding.
     *
     * <p>An exhaustive {@code switch} on purpose: introducing a {@code FindingType} without deciding
     * how to describe it to an operator must not compile.</p>
     */
    public String titleFor(FindingCandidate candidate) {
        return switch (candidate.type()) {
            case INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT -> "Order has no provider payment";
            case PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER -> "Provider payment has no internal order";
            case AMOUNT_MISMATCH -> "Amount mismatch between provider and internal records";
            case CURRENCY_MISMATCH -> "Currency mismatch between provider and internal records";
            case STATUS_MISMATCH -> "Status mismatch between provider and internal records";
            case DUPLICATE_EVENT_DELIVERY -> "Duplicate webhook event delivery";
            case STALE_PAYMENT -> "Payment pending beyond the staleness threshold";
            case REFUND_DISCREPANCY -> "Refund records disagree with the payment";
            case TRANSFER_DISCREPANCY -> "Transfer records disagree with the payment";
            case CONFLICTING_OBSERVATIONS -> "Conflicting observations of the same resource";
            case MISSING_REQUIRED_EVIDENCE -> "Required supporting document is missing";
            case EVIDENCE_AMOUNT_MISMATCH -> "Document total disagrees with the order amount";
            case EVIDENCE_CURRENCY_MISMATCH -> "Document currency disagrees with the order currency";
            case EVIDENCE_REFERENCE_MISMATCH -> "Document refers to a different order or payment";
            case DUPLICATE_EVIDENCE_DOCUMENT -> "Two current documents are duplicates";
            case EVIDENCE_EXTRACTION_UNCERTAIN -> "Extracted values need human confirmation";
            case EVIDENCE_UNREADABLE -> "Document could not be read";
            case EVIDENCE_ORPHANED -> "Document is attached to an unknown order";
        };
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
