package com.aegisx.integrity.evidence;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.domain.EvidenceReadinessStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.entity.EvidenceReadiness;
import com.aegisx.integrity.repository.EvidenceReadinessRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module I: derives the evidence-readiness status for an order and persists it.
 *
 * <p>The derivation itself is a pure function of the order's facts, its current documents and the
 * findings the checks produced. It reads no clock, no provider and no payment state, which is what
 * makes the readiness value reproducible and reviewable: the same evidence always yields the same
 * conclusion, and the conclusion cannot be changed by a payment event.</p>
 *
 * <p>The status can be derived without any findings at all, which matters because a document upload
 * should move readiness immediately even before an assessment has run.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceReadinessService {

    /** The conclusion, before it is persisted. */
    public record Verdict(EvidenceReadinessStatus status,
                          String reason,
                          List<String> requiredTypes,
                          List<String> missingTypes,
                          List<String> uncertainTypes,
                          int blockingFindings,
                          int documentsPresent,
                          int documentsExpected) {

        public Verdict {
            requiredTypes = requiredTypes == null ? List.of() : List.copyOf(requiredTypes);
            missingTypes = missingTypes == null ? List.of() : List.copyOf(missingTypes);
            uncertainTypes = uncertainTypes == null ? List.of() : List.copyOf(uncertainTypes);
        }
    }

    private final EvidenceReadinessRepository readinessRepository;
    private final IntegrityAuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Applies the documented precedence. First match wins; see {@link EvidenceReadinessStatus} for
     * the entry and exit condition of each value.
     *
     * <p>Takes finding <em>types</em> rather than candidates so the same function can be used by the
     * assessment (which has the candidates in hand) and by the read-only status view (which has the
     * persisted cases). Using one function for both is what guarantees the answer on the order screen
     * is the answer the assessment would reach, rather than a second implementation of the same rules
     * that can drift.</p>
     */
    public Verdict derive(List<String> requiredTypes,
                          List<EvidenceCheckService.DocumentFacts> documents,
                          Collection<FindingType> findings) {
        List<String> required = requiredTypes == null ? List.of() : List.copyOf(requiredTypes);
        List<EvidenceCheckService.DocumentFacts> current = documents == null ? List.of() : documents;
        List<FindingType> allFindings = findings == null ? List.of() : List.copyOf(findings);

        List<String> missingTypes = missingTypes(required, current);
        List<String> uncertainTypes = uncertainTypes(current);
        long blocking = allFindings.stream().filter(EvidenceCheckService::isBlocking).count();
        int blockingFindings = (int) blocking;

        int expected = required.size();
        int present = (int) required.stream()
                .filter(type -> current.stream().anyMatch(document -> type.equals(document.documentType())))
                .count();

        // 1. Something is still being read, so no conclusion can be drawn yet.
        boolean inFlight = current.stream().anyMatch(document -> document.processingStatus().isInFlight());
        if (inFlight) {
            return new Verdict(EvidenceReadinessStatus.PROCESSING,
                    countInFlight(current) + " document(s) are still being read, so readiness is not yet "
                            + "decidable",
                    required, missingTypes, uncertainTypes, blockingFindings, present, expected);
        }

        // 2. At least one current document could not be read. Its contents are unknown, not absent.
        List<EvidenceCheckService.DocumentFacts> failed = current.stream()
                .filter(document -> document.processingStatus() == EvidenceProcessingStatus.FAILED)
                .toList();
        if (!failed.isEmpty()) {
            return new Verdict(EvidenceReadinessStatus.PROCESSING_FAILED,
                    "Extraction failed for " + failed.size() + " document(s): "
                            + failed.stream().map(EvidenceCheckService.DocumentFacts::documentType)
                                    .distinct().toList(),
                    required, missingTypes, uncertainTypes, blockingFindings, present, expected);
        }

        // 3. Nothing has been supplied yet. If nothing is required, there is nothing to wait for.
        if (current.isEmpty()) {
            if (required.isEmpty()) {
                return new Verdict(EvidenceReadinessStatus.EVIDENCE_COMPLETE,
                        "No supporting documents are required at this order value",
                        required, missingTypes, uncertainTypes, blockingFindings, 0, 0);
            }
            return new Verdict(EvidenceReadinessStatus.PENDING_EVIDENCE,
                    "No supporting document has been received yet for an order that requires "
                            + required.size() + " document type(s)",
                    required, missingTypes, uncertainTypes, blockingFindings, 0, expected);
        }

        // 4. Something must be fixed before the evidence can be considered complete.
        // Unreadable documents were handled above, so what remains here is an absent, duplicated or
        // contradicting document rather than an unreadable one.
        if (blocking > 0) {
            return new Verdict(EvidenceReadinessStatus.ACTION_REQUIRED,
                    describeBlocking(allFindings, missingTypes),
                    required, missingTypes, uncertainTypes, blockingFindings, present, expected);
        }

        // 5. Present and consistent, but a value a person must confirm.
        if (!uncertainTypes.isEmpty()) {
            return new Verdict(EvidenceReadinessStatus.READY_FOR_REVIEW,
                    "All required documents are present and consistent, but values read from "
                            + uncertainTypes + " have not been confirmed by a person",
                    required, missingTypes, uncertainTypes, 0, present, expected);
        }

        return new Verdict(EvidenceReadinessStatus.EVIDENCE_COMPLETE,
                "Every required document is present, readable, consistent with the order, and its values "
                        + "have been confirmed",
                required, missingTypes, uncertainTypes, 0, present, expected);
    }

    /** Upserts the readiness row for an order. */
    @Transactional
    public EvidenceReadiness persist(UUID workspaceId, UUID orderId, Verdict verdict,
                                     UUID assessmentId, UUID assessedBy) {
        EvidenceReadiness readiness = readinessRepository.findByWorkspaceIdAndOrderId(workspaceId, orderId)
                .orElseGet(() -> EvidenceReadiness.builder()
                        .workspaceId(workspaceId)
                        .orderId(orderId)
                        .build());

        EvidenceReadinessStatus previous = readiness.getStatus();
        readiness.setStatus(verdict.status());
        readiness.setReason(verdict.reason());
        readiness.setRequiredDocumentTypes(toJson(verdict.requiredTypes()));
        readiness.setMissingDocumentTypes(toJson(verdict.missingTypes()));
        readiness.setUncertainDocumentTypes(toJson(verdict.uncertainTypes()));
        readiness.setBlockingFindings(verdict.blockingFindings());
        readiness.setDocumentsPresent(verdict.documentsPresent());
        readiness.setDocumentsExpected(verdict.documentsExpected());
        readiness.setAssessmentId(assessmentId);
        readiness.setLastAssessedAt(Instant.now());
        readiness.setAssessedBy(assessedBy);
        EvidenceReadiness saved = readinessRepository.save(readiness);

        if (previous != verdict.status()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("orderId", orderId.toString());
            detail.put("previousStatus", previous == null ? "NONE" : previous.name());
            detail.put("status", verdict.status().name());
            detail.put("reason", verdict.reason());
            detail.put("missingDocumentTypes", verdict.missingTypes());
            auditService.record("EVIDENCE_READINESS_CHANGED", "EVIDENCE", "SYSTEM", workspaceId, detail,
                    orderId, null, null, null, null, assessmentId, assessedBy, false);
            log.info("Evidence readiness for order {} changed from {} to {}",
                    orderId, previous, verdict.status());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EvidenceReadiness> forWorkspace(UUID workspaceId) {
        return readinessRepository.findAllByWorkspaceIdOrderByLastAssessedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<EvidenceReadiness> forOrder(UUID workspaceId, UUID orderId) {
        return readinessRepository.findByWorkspaceIdAndOrderId(workspaceId, orderId);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> missingTypes(List<String> required,
                                            List<EvidenceCheckService.DocumentFacts> current) {
        List<String> missing = new ArrayList<>();
        for (String type : required) {
            boolean present = current.stream().anyMatch(document -> type.equals(document.documentType()));
            if (!present) {
                missing.add(type);
            }
        }
        return List.copyOf(missing);
    }

    private static List<String> uncertainTypes(List<EvidenceCheckService.DocumentFacts> current) {
        return current.stream()
                .filter(document -> document.processingStatus() == EvidenceProcessingStatus.EXTRACTED)
                .filter(document -> !document.uncertainFields().isEmpty())
                .map(EvidenceCheckService.DocumentFacts::documentType)
                .distinct()
                .toList();
    }

    private static long countInFlight(List<EvidenceCheckService.DocumentFacts> current) {
        return current.stream().filter(document -> document.processingStatus().isInFlight()).count();
    }

    private static String describeBlocking(List<FindingType> findings, List<String> missingTypes) {
        List<String> reasons = new ArrayList<>();
        if (!missingTypes.isEmpty()) {
            reasons.add("missing: " + String.join(", ", missingTypes));
        }
        List<String> other = findings.stream()
                .filter(EvidenceCheckService::isBlocking)
                .filter(finding -> finding != FindingType.MISSING_REQUIRED_EVIDENCE)
                .filter(finding -> finding != FindingType.EVIDENCE_UNREADABLE)
                .map(Enum::name)
                .distinct()
                .toList();
        if (!other.isEmpty()) {
            reasons.add("disagreements: " + String.join(", ", other));
        }
        return "Supporting evidence needs attention before it can be relied on (" + String.join("; ", reasons) + ")";
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }
}
