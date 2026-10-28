package com.aegisx.integrity.evidence;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.ReconciliationTrigger;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.EvidenceExtraction;
import com.aegisx.integrity.entity.EvidenceReadiness;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.ReconciliationFinding;
import com.aegisx.integrity.entity.ReconciliationRun;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.reconciliation.ExceptionUpserter;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.repository.EvidenceDocumentRepository;
import com.aegisx.integrity.repository.EvidenceExtractionRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.ReconciliationFindingRepository;
import com.aegisx.integrity.repository.ReconciliationRunRepository;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Module I: one evidence assessment pass over a workspace or a single order.
 *
 * <p>An assessment is recorded as a {@link ReconciliationRun} with trigger
 * {@link ReconciliationTrigger#EVIDENCE_ASSESSMENT}. That is deliberate: the console's run history
 * then shows whether the last thing that examined this workspace looked at the money or at the
 * paperwork, using the table that already exists rather than a parallel one.</p>
 *
 * <p>Findings flow into the <strong>same</strong> exception queue as payment findings, through the
 * same {@link ExceptionUpserter}. An operator therefore works one queue, with one deduplication
 * rule and one reopen rule. A second queue with subtly different behaviour is the failure mode this
 * design exists to avoid.</p>
 *
 * <p>The payment dimension is never touched. No method here reads or writes a payment's state, so an
 * assessment cannot advance, capture, reverse or refund anything.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceAssessmentService {

    /** What one assessment did. */
    public record AssessmentReport(UUID runId,
                                   int ordersAssessed,
                                   int documentsChecked,
                                   int findingsCount,
                                   int newExceptions,
                                   int reopenedExceptions,
                                   int refreshedExceptions,
                                   Map<String, String> readinessByOrder,
                                   List<String> notes) { }

    private final WorkspaceService workspaceService;
    private final PaymentOrderRepository orderRepository;
    private final EvidenceDocumentRepository documentRepository;
    private final EvidenceExtractionRepository extractionRepository;
    private final EvidenceRequirementResolver requirementResolver;
    private final EvidenceCheckService checkService;
    private final EvidenceReadinessService readinessService;
    private final ExceptionUpserter exceptionUpserter;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final IntegrityAuditService auditService;
    private final com.aegisx.integrity.config.IntegrityProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Assesses every order in the workspace, or one order when {@code orderId} is given.
     */
    @Transactional
    public AssessmentReport assess(UUID workspaceId, UUID orderId, ActorContext.Actor actor,
                                   ReconciliationTrigger trigger) {
        Workspace workspace = workspaceService.get(workspaceId);
        UUID actorUserId = actor == null ? null : actor.userId();
        String actorLabel = actor == null ? "SYSTEM" : actor.label();

        List<PaymentOrder> orders = orderId == null
                ? orderRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                : List.of(orderRepository.findById(orderId)
                        .filter(order -> order.getWorkspaceId().equals(workspaceId))
                        .orElseThrow(() -> new com.aegisx.integrity.exception.IntegrityNotFoundException(
                                "Order " + orderId + " does not exist in this workspace")));

        ReconciliationRun run = runRepository.save(ReconciliationRun.builder()
                .workspaceId(workspaceId)
                .trigger(trigger == null ? ReconciliationTrigger.EVIDENCE_ASSESSMENT : trigger)
                .strategy(ReconciliationStrategy.PROPOSED_EVIDENCE_BASED)
                .startedAt(Instant.now())
                .ordersChecked(orders.size())
                .providerReachable(true)
                .notes("Evidence assessment: required documents, readability and cross-record consistency")
                .triggeredBy(actorUserId)
                .build());

        Map<UUID, List<EvidenceExtraction>> extractionsByDocument = loadExtractions(workspaceId);

        int documentsChecked = 0;
        int findingsCount = 0;
        int newExceptions = 0;
        int reopened = 0;
        int refreshed = 0;
        Map<String, String> readinessByOrder = new LinkedHashMap<>();

        for (PaymentOrder order : orders) {
            List<EvidenceDocument> current =
                    documentRepository.findAllByOrderIdAndSupersededAtIsNullOrderByCreatedAtDesc(
                            order.getId());
            documentsChecked += current.size();

            EvidenceRequirementResolver.Requirements requirements = requirementResolver.resolve(order);
            List<String> requiredTypes = EvidenceCheckService.canonicalTypes(requirements.required());

            List<EvidenceCheckService.DocumentFacts> facts = current.stream()
                    .map(document -> toFacts(document, extractionsByDocument))
                    .toList();

            EvidenceCheckService.Input input = new EvidenceCheckService.Input(
                    new EvidenceCheckService.OrderFacts(order.getId(), order.getInternalReference(),
                            order.getProviderOrderId(), order.getExpectedAmountMinor(), order.getCurrency()),
                    facts,
                    requiredTypes,
                    properties.getEvidence().getAmountToleranceMinor());

            List<FindingCandidate> findings = checkService.evaluate(input);

            for (FindingCandidate candidate : findings) {
                findingsCount++;
                ExceptionUpserter.Upsert upsert =
                        exceptionUpserter.upsert(workspaceId, run, candidate, actorUserId);
                ExceptionCase exception = upsert.exception();
                if (upsert.created()) {
                    newExceptions++;
                } else if (upsert.reopened()) {
                    reopened++;
                } else {
                    refreshed++;
                }
                findingRepository.save(ReconciliationFinding.builder()
                        .runId(run.getId())
                        .workspaceId(workspaceId)
                        .orderId(candidate.orderId())
                        .paymentId(candidate.paymentId())
                        .findingType(candidate.type())
                        .severity(candidate.severity())
                        .certainty(candidate.certainty())
                        .summary(truncate(candidate.summary(), 490))
                        .evidenceJson(toJson(candidate.evidence()))
                        .missingEvidenceJson(toJson(candidate.missingEvidence()))
                        .exceptionId(exception.getId())
                        .build());
                exceptionUpserter.attachRecommendations(workspaceId, exception, candidate, null);
            }

            EvidenceReadinessService.Verdict verdict = readinessService.derive(requiredTypes, facts,
                    findings.stream().map(FindingCandidate::type).toList());
            EvidenceReadiness readiness =
                    readinessService.persist(workspaceId, order.getId(), verdict, run.getId(), actorUserId);
            readinessByOrder.put(order.getInternalReference(), readiness.getStatus().name());
        }

        // Documents whose order is gone. These can never justify a payment, so they are reported
        // rather than silently ignored.
        findingsCount += reportOrphans(workspace, orders, run, actorLabel);

        run.setCompletedAt(Instant.now());
        run.setFindingsCount(findingsCount);
        run.setNewExceptions(newExceptions);
        run.setReopenedExceptions(reopened);
        run.setRefreshedExceptions(refreshed);
        run.setDocumentsChecked(documentsChecked);
        runRepository.save(run);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", run.getId().toString());
        detail.put("ordersAssessed", orders.size());
        detail.put("documentsChecked", documentsChecked);
        detail.put("findings", findingsCount);
        detail.put("newExceptions", newExceptions);
        detail.put("reopenedExceptions", reopened);
        auditService.record("EVIDENCE_ASSESSMENT_COMPLETED", "EVIDENCE", actorLabel, workspaceId, detail,
                orderId, null, null, null, null, run.getId(), actorUserId, false);

        log.info("Evidence assessment {} for workspace {}: {} order(s), {} document(s), {} finding(s)",
                run.getId(), workspaceId, orders.size(), documentsChecked, findingsCount);

        return new AssessmentReport(run.getId(), orders.size(), documentsChecked, findingsCount,
                newExceptions, reopened, refreshed, Map.copyOf(readinessByOrder), List.of());
    }

    /**
     * Convenience used by write paths: assess one order and report the resulting readiness.
     */
    @Transactional
    public EvidenceReadiness assessOrder(UUID workspaceId, UUID orderId, ActorContext.Actor actor) {
        AssessmentReport report = assess(workspaceId, orderId, actor, ReconciliationTrigger.EVIDENCE_ASSESSMENT);
        return readinessService.forOrder(workspaceId, orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Assessment " + report.runId() + " produced no readiness row for order " + orderId));
    }

    // ------------------------------------------------------------------ helpers

    private int reportOrphans(Workspace workspace, List<PaymentOrder> orders,
                              ReconciliationRun run, String actorLabel) {
        List<UUID> knownOrderIds = orders.stream().map(PaymentOrder::getId).toList();
        int reported = 0;
        for (EvidenceDocument document : documentRepository
                .findAllByWorkspaceIdAndSupersededAtIsNull(workspace.getId())) {
            if (knownOrderIds.contains(document.getOrderId())) {
                continue;
            }
            if (orderRepository.findById(document.getOrderId()).isPresent()) {
                // Belongs to another order in this workspace; a single-order assessment skips it.
                continue;
            }
            FindingCandidate candidate = com.aegisx.integrity.reconciliation.model.CandidateFactory.of(
                    com.aegisx.integrity.domain.FindingType.EVIDENCE_ORPHANED,
                    com.aegisx.integrity.domain.CertaintyLevel.CERTAIN,
                    null, null, null, null,
                    document.getId().toString(),
                    "Document " + document.getId() + " is attached to order " + document.getOrderId()
                            + ", which no longer exists in this workspace. It cannot justify any payment.",
                    List.of("document id = " + document.getId(),
                            "document type = " + document.getDocumentType(),
                            "referenced order = " + document.getOrderId()),
                    List.of("a decision about which order this document belongs to"));
            ExceptionUpserter.Upsert upsert = exceptionUpserter.upsert(workspace.getId(), run, candidate, null);
            findingRepository.save(ReconciliationFinding.builder()
                    .runId(run.getId())
                    .workspaceId(workspace.getId())
                    .findingType(candidate.type())
                    .severity(candidate.severity())
                    .certainty(candidate.certainty())
                    .summary(truncate(candidate.summary(), 490))
                    .evidenceJson(toJson(candidate.evidence()))
                    .missingEvidenceJson(toJson(candidate.missingEvidence()))
                    .exceptionId(upsert.exception().getId())
                    .build());
            exceptionUpserter.attachRecommendations(workspace.getId(), upsert.exception(), candidate, null);
            reported++;
            log.warn("Evidence document {} references unknown order {} in workspace {}",
                    document.getId(), document.getOrderId(), workspace.getId());
        }
        return reported;
    }

    /**
     * The current documents of an order as the checks see them.
     *
     * <p>Exposed so the read-only status view can derive the same readiness verdict the assessment
     * would reach, instead of depending on an assessment having already run. A status screen that
     * answers differently depending on whether a background pass has happened yet is worse than no
     * status screen.</p>
     */
    @Transactional(readOnly = true)
    public List<EvidenceCheckService.DocumentFacts> factsForOrder(UUID workspaceId, UUID orderId) {
        Map<UUID, List<EvidenceExtraction>> extractionsByDocument = loadExtractions(workspaceId);
        return documentRepository
                .findAllByOrderIdAndSupersededAtIsNullOrderByCreatedAtDesc(orderId).stream()
                .map(document -> toFacts(document, extractionsByDocument))
                .toList();
    }

    private Map<UUID, List<EvidenceExtraction>> loadExtractions(UUID workspaceId) {
        Map<UUID, List<EvidenceExtraction>> grouped = new HashMap<>();
        for (EvidenceExtraction extraction : extractionRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId)) {
            grouped.computeIfAbsent(extraction.getDocumentId(), key -> new ArrayList<>()).add(extraction);
        }
        return grouped;
    }

    private EvidenceCheckService.DocumentFacts toFacts(
            EvidenceDocument document, Map<UUID, List<EvidenceExtraction>> extractionsByDocument) {
        List<EvidenceExtraction> extractions = extractionsByDocument.getOrDefault(document.getId(), List.of());

        // Confirmation is per field, not per document. Each human correction records the exact field it
        // covered, so correcting the currency must not silently clear an uncertain total. Using a single
        // document-level flag here would let one correction vouch for values nobody had looked at.
        java.util.Set<String> confirmedFields = new java.util.HashSet<>();
        for (EvidenceExtraction extraction : extractions) {
            if (extraction.isHumanCorrection()) {
                confirmedFields.addAll(parseJsonObjectKeys(extraction.getExtractedData()));
            }
        }

        // The newest machine reading, which is the one whose uncertainty set applies.
        Optional<EvidenceExtraction> latestMachine = extractions.stream()
                .filter(extraction -> !extraction.isHumanCorrection())
                .findFirst();
        List<String> uncertain = latestMachine
                .map(extraction -> parseJsonArray(extraction.getUncertainFields()))
                .orElseGet(List::of).stream()
                .filter(field -> !confirmedFields.contains(field))
                .toList();
        List<String> absent = latestMachine
                .map(extraction -> parseJsonArray(extraction.getAbsentFields()))
                .orElseGet(List::of).stream()
                .filter(field -> !confirmedFields.contains(field))
                .toList();

        return new EvidenceCheckService.DocumentFacts(
                document.getId(),
                document.getDocumentType().getCanonicalType().name(),
                document.getProcessingStatus(),
                document.getExtractedInvoiceNumber(),
                document.getExtractedAmountMinor(),
                document.getExtractedCurrency(),
                document.getExtractedOrderReference(),
                document.getExtractedPaymentReference(),
                uncertain,
                absent,
                document.getFileHash(),
                document.getProcessingError());
    }

    /** Keys of a JSON object, used to read which field a correction covered. */
    private List<String> parseJsonObjectKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
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
