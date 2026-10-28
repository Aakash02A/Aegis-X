package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.domain.EvidenceReadinessStatus;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.EvidenceExtraction;
import com.aegisx.integrity.entity.EvidenceReadiness;
import com.aegisx.integrity.evidence.EvidenceAssessmentService;
import com.aegisx.integrity.evidence.EvidenceDocumentService;
import com.aegisx.integrity.evidence.EvidenceIntelligenceService;
import com.aegisx.integrity.evidence.EvidenceRequirementResolver;
import com.aegisx.integrity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module I REST surface: supporting documents, their extraction, and the evidence side of a
 * payment's status.
 *
 * <p>Mounted under {@code /api/integrity/workspaces/{workspaceId}} so it shares the workspace scoping
 * and the role rules of the rest of the integrity engine. Workspace isolation is enforced in the
 * services by filtering on {@code workspaceId} in every query, never by trusting an identifier from
 * the request body.</p>
 *
 * <p>Two safety properties are visible in this class:</p>
 * <ul>
 *   <li>Nothing here can move money. The only writes are documents, extractions, findings and
 *       readiness rows.</li>
 *   <li>Uploading is not a privileged operation that bypasses review: a document merely becomes
 *       evidence, and any problem it reveals becomes an exception a person must resolve.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/evidence")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
@Tag(name = "Evidence intelligence",
        description = "Supporting documents, extraction, consistency checks and evidence readiness")
public class IntegrityEvidenceController {

    private final EvidenceIntelligenceService evidenceService;
    private final EvidenceDocumentService documentService;
    private final ActorContext actorContext;

    // --------------------------------------------------------------- documents

    @Operation(summary = "Upload a supporting document and process it")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EvidenceIntelligenceService.UploadOutcome>> upload(
            @PathVariable UUID workspaceId,
            @RequestParam("orderId") UUID orderId,
            @RequestParam("documentType") DocumentType documentType,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        ActorContext.Actor actor = actorContext.resolve(authentication);
        EvidenceIntelligenceService.UploadOutcome outcome =
                evidenceService.receiveAndProcess(workspaceId, orderId, documentType, file, actor);
        String message = outcome.extractionFailed()
                ? "The document was stored but could not be read. " + outcome.extractionMessage()
                : outcome.supersededPrevious()
                        ? "Document stored and read; it replaced an earlier document of the same type"
                        : "Document stored and read";
        return ResponseEntity.ok(ApiResponse.success(outcome, message));
    }

    @Operation(summary = "List the documents held for an order")
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<List<EvidenceDocument>>> list(
            @PathVariable UUID workspaceId,
            @RequestParam("orderId") UUID orderId,
            @RequestParam(value = "includeSuperseded", defaultValue = "false") boolean includeSuperseded) {
        return ResponseEntity.ok(ApiResponse.success(
                evidenceService.documents(workspaceId, orderId, includeSuperseded),
                includeSuperseded
                        ? "Document history including superseded versions"
                        : "Current documents for the order"));
    }

    @Operation(summary = "Download a stored document")
    @GetMapping("/documents/{documentId}/content")
    public ResponseEntity<Resource> download(@PathVariable UUID workspaceId,
                                             @PathVariable UUID documentId) {
        EvidenceDocument document = documentService.require(workspaceId, documentId);
        Resource resource = documentService.load(workspaceId, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.getOriginalFilename() + "\"")
                .body(resource);
    }

    @Operation(summary = "Re-read a document")
    @PostMapping("/documents/{documentId}/extract")
    public ResponseEntity<ApiResponse<EvidenceIntelligenceService.UploadOutcome>> extract(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                evidenceService.reExtract(workspaceId, documentId, actorContext.resolve(authentication)),
                "Extraction re-run"));
    }

    @Operation(summary = "List the extraction history of a document, including human corrections")
    @GetMapping("/documents/{documentId}/extractions")
    public ResponseEntity<ApiResponse<List<EvidenceExtraction>>> extractions(
            @PathVariable UUID workspaceId, @PathVariable UUID documentId) {
        return ResponseEntity.ok(ApiResponse.success(
                evidenceService.extractionHistory(workspaceId, documentId),
                "Extraction history, oldest first"));
    }

    /** Body of a correction. Field names are the ones the extractor publishes. */
    public record CorrectionRequest(String field, String value) { }

    @Operation(summary = "Confirm or correct a value read from a document")
    @PostMapping("/documents/{documentId}/corrections")
    public ResponseEntity<ApiResponse<EvidenceIntelligenceService.UploadOutcome>> correct(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @RequestBody CorrectionRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                evidenceService.correct(workspaceId, documentId, request.field(), request.value(),
                        actorContext.resolve(authentication)),
                "Correction recorded; the document's values were re-assessed"));
    }

    // ------------------------------------------------------------ requirements

    /** The requirement list plus the policy reason, so the UI can explain why a document is wanted. */
    public record RequirementsView(List<DocumentType> required, String rationale) { }

    @Operation(summary = "Which documents this order requires, and why")
    @GetMapping("/requirements")
    public ResponseEntity<ApiResponse<RequirementsView>> requirements(
            @PathVariable UUID workspaceId, @RequestParam("orderId") UUID orderId) {
        EvidenceRequirementResolver.Requirements requirements =
                evidenceService.requirements(workspaceId, orderId);
        return ResponseEntity.ok(ApiResponse.success(
                new RequirementsView(requirements.required(), requirements.rationale()),
                "Requirement list derived from the order value and the configured evidence policy"));
    }

    // -------------------------------------------------------------- readiness

    @Operation(summary = "Evidence readiness for every assessed order in the workspace")
    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<List<EvidenceReadiness>>> readiness(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(evidenceService.readinessOverview(workspaceId),
                "Evidence readiness by order"));
    }

    @Operation(summary = "Run an evidence assessment")
    @PostMapping("/assess")
    public ResponseEntity<ApiResponse<EvidenceAssessmentService.AssessmentReport>> assess(
            @PathVariable UUID workspaceId,
            @RequestParam(value = "orderId", required = false) UUID orderId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                evidenceService.assess(workspaceId, orderId, actorContext.resolve(authentication)),
                orderId == null
                        ? "Every order in the workspace was assessed"
                        : "The order was assessed"));
    }

    @Operation(summary = "The four independent statuses for one order")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<EvidenceIntelligenceService.OrderStatusSummary>> summary(
            @PathVariable UUID workspaceId, @RequestParam("orderId") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.success(evidenceService.summary(workspaceId, orderId),
                "Payment status, evidence status, exception status and recommended action are reported "
                        + "separately and are not derived from one another"));
    }

    // ----------------------------------------------- capability advertisement

    /**
     * What the evidence dimension can and cannot do, so the console can state it rather than imply it.
     */
    public record CapabilityView(String processingStatusModel,
                                 String readinessModel,
                                 List<String> mutuallyExclusiveFrom,
                                 String limitation) { }

    @Operation(summary = "Describe the evidence model, including its limits")
    @GetMapping("/model")
    public ResponseEntity<ApiResponse<CapabilityView>> model(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(new CapabilityView(
                String.join(", ", java.util.Arrays.stream(EvidenceProcessingStatus.values())
                        .map(Enum::name).toList()),
                String.join(", ", java.util.Arrays.stream(EvidenceReadinessStatus.values())
                        .map(Enum::name).toList()),
                List.of("payment.providerState", "payment.state", "settlement lifecycle"),
                "Reading a document successfully proves it was legible, not that it is genuine. "
                        + "Evidence status is therefore a statement about the records, never about the "
                        + "authenticity of the parties involved."),
                "Evidence model"));
    }

    /** Server time and whether extraction is configured, used as a cheap readiness probe by the UI. */
    @Operation(summary = "Evidence subsystem liveness")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("checkedAt", Instant.now().toString(), "workspaceId", workspaceId.toString()),
                "Evidence subsystem reachable"));
    }
}
