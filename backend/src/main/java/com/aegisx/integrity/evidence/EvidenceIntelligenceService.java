package com.aegisx.integrity.evidence;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.EvidenceReadinessStatus;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.EvidenceExtraction;
import com.aegisx.integrity.entity.EvidenceReadiness;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.repository.EvidenceDocumentRepository;
import com.aegisx.integrity.repository.ExceptionCaseRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.RecoveryRecommendationRepository;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Module I: the evidence workflow as the console uses it.
 *
 * <p>Exists to keep the write paths honest about ordering. A document upload is only useful if it is
 * read and then re-assessed, and a correction is only useful if the assessment is re-run, so those
 * sequences live here rather than being left to each caller to remember. The individual services
 * remain usable on their own for tests and for the payment side.</p>
 *
 * <p>{@link #summary} is the product's central view. It answers four separate questions and never
 * merges them:</p>
 * <ol>
 *   <li><strong>Payment status</strong> — what the provider and our own record say about the money.</li>
 *   <li><strong>Evidence status</strong> — whether the supporting records are present and consistent.</li>
 *   <li><strong>Exception status</strong> — whether anything needs investigating.</li>
 *   <li><strong>Recommended action</strong> — what a person should do next.</li>
 * </ol>
 * <p>Reporting a single "status" would make a successfully captured payment with no invoice look
 * identical to one that is complete, which is precisely the confusion the product exists to remove.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceIntelligenceService {

    /** Outcome of uploading a document and processing it end to end. */
    public record UploadOutcome(EvidenceDocument document,
                                boolean supersededPrevious,
                                UUID supersededDocumentId,
                                boolean extractionFailed,
                                String extractionMessage,
                                EvidenceReadiness readiness) { }

    /**
     * The four independent answers for one order.
     *
     * @param providerPaymentStatus provider-confirmed state, or null when no payment has been observed
     * @param internalPaymentStatus Aegis-X's own state for the payment
     * @param evidenceStatus        completeness and consistency of the supporting records
     * @param exceptionStatus       {@code NONE} when nothing is outstanding, otherwise the most urgent
     *                              open state
     */
    public record OrderStatusSummary(UUID orderId,
                                     String internalReference,
                                     String orderState,
                                     PaymentState providerPaymentStatus,
                                     PaymentState internalPaymentStatus,
                                     boolean paymentSimulated,
                                     EvidenceReadinessStatus evidenceStatus,
                                     String evidenceReason,
                                     List<String> missingDocumentTypes,
                                     List<String> uncertainDocumentTypes,
                                     String exceptionStatus,
                                     long openExceptionCount,
                                     long resolvedExceptionCount,
                                     List<String> openFindingTypes,
                                     String recommendedAction,
                                     boolean recommendedActionRequiresApproval,
                                     boolean financialActionRecommended,
                                     int documentsPresent,
                                     int documentsExpected) { }

    private final WorkspaceService workspaceService;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final EvidenceDocumentRepository documentRepository;
    private final EvidenceDocumentService documentService;
    private final EvidenceExtractionService extractionService;
    private final EvidenceAssessmentService assessmentService;
    private final EvidenceReadinessService readinessService;
    private final EvidenceRequirementResolver requirementResolver;
    private final ExceptionCaseRepository exceptionRepository;
    private final RecoveryRecommendationRepository recommendationRepository;
    private final IntegrityAuditService auditService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Upload, validate, store, read, then re-assess. Nothing is left half-done for a later caller. */
    @Transactional
    public UploadOutcome receiveAndProcess(UUID workspaceId, UUID orderId, DocumentType requestedType,
                                           MultipartFile file, ActorContext.Actor actor) {
        workspaceService.get(workspaceId);
        String filename = EvidenceDocumentService.safeFilename(file == null ? null : file.getOriginalFilename(),
                "evidence-document");
        byte[] content = EvidenceDocumentService.readBytes(file);

        EvidenceDocumentService.Receipt receipt = documentService.receive(workspaceId, orderId, requestedType,
                file, filename, content,
                actor == null ? "UNKNOWN" : actor.label(), actor == null ? null : actor.userId(), false);

        EvidenceExtractionService.Outcome extraction =
                extractionService.extract(workspaceId, receipt.document().getId(), false);

        EvidenceReadiness readiness =
                assessmentService.assessOrder(workspaceId, orderId, actor);

        auditService.record("EVIDENCE_DOCUMENT_PROCESSED", "EVIDENCE",
                actor == null ? "UNKNOWN" : actor.label(), workspaceId,
                Map.of("documentId", receipt.document().getId().toString(),
                        "extractionFailed", extraction.failed(),
                        "readiness", readiness.getStatus().name()),
                orderId, null, null, null, null, null,
                actor == null ? null : actor.userId(), false);

        return new UploadOutcome(receipt.document(), receipt.supersededPrevious(),
                receipt.supersededDocumentId(), extraction.failed(), extraction.message(), readiness);
    }

    /** Re-reads a document, for example after a legible copy replaced an unreadable one. */
    @Transactional
    public UploadOutcome reExtract(UUID workspaceId, UUID documentId, ActorContext.Actor actor) {
        EvidenceDocument document = documentService.require(workspaceId, documentId);
        EvidenceExtractionService.Outcome extraction = extractionService.extract(workspaceId, documentId, false);
        EvidenceReadiness readiness = assessmentService.assessOrder(workspaceId, document.getOrderId(), actor);
        return new UploadOutcome(document, false, null, extraction.failed(), extraction.message(), readiness);
    }

    /** Records a human correction and re-assesses, because a correction can clear a finding. */
    @Transactional
    public UploadOutcome correct(UUID workspaceId, UUID documentId, String field, String value,
                                 ActorContext.Actor actor) {
        EvidenceDocument document = documentService.require(workspaceId, documentId);
        EvidenceExtractionService.Outcome outcome =
                extractionService.correct(workspaceId, documentId, field, value, actor, false);
        EvidenceReadiness readiness = assessmentService.assessOrder(workspaceId, document.getOrderId(), actor);
        return new UploadOutcome(document, false, null, false, outcome.message(), readiness);
    }

    @Transactional
    public EvidenceAssessmentService.AssessmentReport assess(UUID workspaceId, UUID orderId,
                                                             ActorContext.Actor actor) {
        workspaceService.get(workspaceId);
        return assessmentService.assess(workspaceId, orderId, actor, null);
    }

    @Transactional(readOnly = true)
    public EvidenceRequirementResolver.Requirements requirements(UUID workspaceId, UUID orderId) {
        PaymentOrder order = documentService.requireOrder(workspaceId, orderId);
        return requirementResolver.resolve(order);
    }

    @Transactional(readOnly = true)
    public List<EvidenceReadiness> readinessOverview(UUID workspaceId) {
        workspaceService.get(workspaceId);
        return readinessService.forWorkspace(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<EvidenceDocument> documents(UUID workspaceId, UUID orderId, boolean includeSuperseded) {
        return includeSuperseded
                ? documentService.history(workspaceId, orderId)
                : documentService.current(workspaceId, orderId);
    }

    @Transactional(readOnly = true)
    public List<EvidenceExtraction> extractionHistory(UUID workspaceId, UUID documentId) {
        return extractionService.history(workspaceId, documentId);
    }

    @Transactional(readOnly = true)
    public Optional<EvidenceExtraction> latestExtraction(UUID workspaceId, UUID documentId) {
        return extractionService.latest(workspaceId, documentId);
    }

    /**
     * Builds the four-status view for one order.
     *
     * <p>Every value is read from persisted rows. Nothing is defaulted, estimated or invented, and a
     * missing piece of data is reported as missing rather than as zero or "OK".</p>
     */
    @Transactional(readOnly = true)
    public OrderStatusSummary summary(UUID workspaceId, UUID orderId) {
        PaymentOrder order = documentService.requireOrder(workspaceId, orderId);

        List<PaymentRecord> payments = paymentRepository.findByOrderId(orderId);
        PaymentRecord primary = payments.stream()
                .max(Comparator.comparingInt(PaymentRecord::getStateEvidenceRank)
                        .thenComparing(record -> record.getLastObservedAt() == null
                                ? java.time.Instant.EPOCH : record.getLastObservedAt()))
                .orElse(null);

        List<ExceptionCase> cases =
                exceptionRepository.findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(workspaceId, orderId);
        List<ExceptionCase> open = cases.stream()
                .filter(exception -> exception.getStatus() == ExceptionStatus.OPEN
                        || exception.getStatus() == ExceptionStatus.IN_REVIEW)
                .toList();
        long resolved = cases.stream()
                .filter(exception -> exception.getStatus() == ExceptionStatus.RESOLVED)
                .count();

        // Readiness is derived here from the current documents and the persisted cases rather than read
        // from the stored row, so the answer is correct even before any assessment has run. It is the
        // same function the assessment uses, so the two cannot disagree.
        List<String> requiredTypes =
                EvidenceCheckService.canonicalTypes(requirementResolver.resolve(order).required());
        List<EvidenceCheckService.DocumentFacts> facts = assessmentService.factsForOrder(workspaceId, orderId);
        EvidenceReadinessService.Verdict verdict = readinessService.derive(requiredTypes, facts,
                cases.stream().map(ExceptionCase::getFindingType).toList());
        // The stored row is still reported alongside, because it records when the last assessment ran.
        EvidenceReadiness storedReadiness = readinessService.forOrder(workspaceId, orderId).orElse(null);

        String exceptionStatus = summarizeExceptions(cases, open);
        ExceptionCase mostUrgent = open.stream()
                .min(Comparator.comparing(exception -> exception.getSeverity().ordinal()))
                .orElse(null);

        String recommendedAction = null;
        boolean requiresApproval = false;
        boolean financial = false;
        if (mostUrgent != null) {
            List<RecoveryRecommendation> recommendations =
                    recommendationRepository.findByExceptionIdOrderByCreatedAtAsc(mostUrgent.getId());
            Optional<RecoveryRecommendation> primaryRecommendation = recommendations.stream()
                    .filter(recommendation -> recommendation.getStatus() == RecommendationStatus.PROPOSED)
                    .findFirst()
                    .or(() -> recommendations.stream().findFirst());
            if (primaryRecommendation.isPresent()) {
                RecoveryRecommendation recommendation = primaryRecommendation.get();
                recommendedAction = recommendation.getRationale();
                requiresApproval = recommendation.isRequiresHumanApproval();
                financial = recommendation.getCategory() == RecommendationCategory.FINANCIAL;
            } else {
                recommendedAction = mostUrgent.getRecommendedAction();
            }
        }

        return new OrderStatusSummary(
                order.getId(),
                order.getInternalReference(),
                order.getState() == null ? null : order.getState().name(),
                primary == null ? null : primary.getProviderState(),
                primary == null ? null : primary.getState(),
                primary != null && primary.isSimulated(),
                verdict.status(),
                verdict.reason() + (storedReadiness == null
                        ? " (no assessment has run for this order yet; this is derived from the current "
                                + "documents)"
                        : ""),
                verdict.missingTypes(),
                verdict.uncertainTypes(),
                exceptionStatus,
                open.size(),
                resolved,
                open.stream().map(exception -> exception.getFindingType().name()).distinct().toList(),
                recommendedAction,
                requiresApproval,
                financial,
                verdict.documentsPresent(),
                verdict.documentsExpected());
    }

    // ------------------------------------------------------------------ helpers

    private static String summarizeExceptions(List<ExceptionCase> cases, List<ExceptionCase> open) {
        if (cases.isEmpty()) {
            return "NONE";
        }
        if (open.isEmpty()) {
            boolean anyDismissed = cases.stream()
                    .anyMatch(exception -> exception.getStatus() == ExceptionStatus.DISMISSED);
            return anyDismissed ? "DISMISSED" : "RESOLVED";
        }
        boolean anyInReview = open.stream()
                .anyMatch(exception -> exception.getStatus() == ExceptionStatus.IN_REVIEW);
        return anyInReview ? "IN_REVIEW" : "OPEN";
    }

    private List<String> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Convenience for callers that need to be told plainly when a document is not usable. */
    public static void requireUsable(EvidenceDocument document) {
        if (!document.isActive()) {
            throw new IntegrityConflictException("This document has been superseded by a newer one");
        }
    }
}
