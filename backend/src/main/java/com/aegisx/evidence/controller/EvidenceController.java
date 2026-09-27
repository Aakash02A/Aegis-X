package com.aegisx.evidence.controller;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.dto.EvidenceChecklistResponse;
import com.aegisx.evidence.dto.EvidenceRequirementRequest;
import com.aegisx.evidence.dto.MissingDocumentsResponse;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.evidence.service.EvidenceChecklistService;
import com.aegisx.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST Controller for Evidence Checklist Management.
 * Provides endpoints for managing document requirements and tracking evidence collection.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/evidence")
@RequiredArgsConstructor
@Tag(name = "Evidence Management", description = "Evidence checklist and document requirement management")
public class EvidenceController {

    private final EvidenceChecklistService evidenceChecklistService;

    @Operation(summary = "Get evidence checklist for transaction", 
               description = "Retrieves the evidence checklist showing document requirements and upload status")
    @GetMapping("/checklist/{transactionId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceChecklistResponse>> getChecklist(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.debug("Getting evidence checklist for transaction: {}", transactionId);
        
        Optional<EvidenceChecklist> checklist = evidenceChecklistService.getChecklistByTransactionId(transactionId);
        
        if (checklist.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(null, "No evidence checklist found for transaction"));
        }
        
        EvidenceChecklistResponse response = EvidenceChecklistResponse.fromEntity(checklist.get());
        return ResponseEntity.ok(ApiResponse.success(response, "Evidence checklist retrieved successfully"));
    }

    @Operation(summary = "Get missing documents for transaction",
               description = "Returns list of documents still required for transaction completion")
    @GetMapping("/missing/{transactionId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<MissingDocumentsResponse>> getMissingDocuments(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.debug("Getting missing documents for transaction: {}", transactionId);
        
        List<DocumentType> missingTypes = evidenceChecklistService.getMissingDocuments(transactionId);
        Optional<EvidenceChecklist> checklist = evidenceChecklistService.getChecklistByTransactionId(transactionId);
        
        int totalRequired = 1; // Default: at least invoice
        int totalUploaded = 0;
        
        if (checklist.isPresent()) {
            EvidenceChecklistResponse checklistResponse = EvidenceChecklistResponse.fromEntity(checklist.get());
            totalRequired = checklistResponse.getTotalRequiredDocuments();
            totalUploaded = checklistResponse.getUploadedDocumentsCount();
        }
        
        MissingDocumentsResponse response = MissingDocumentsResponse.create(
                transactionId, missingTypes, totalRequired, totalUploaded);
        
        return ResponseEntity.ok(ApiResponse.success(response, "Missing documents retrieved successfully"));
    }

    @Operation(summary = "Update document requirements",
               description = "Updates which documents are required for a specific transaction")
    @PutMapping("/requirements")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceChecklistResponse>> updateRequirements(
            @Valid @RequestBody EvidenceRequirementRequest request) {
        
        log.info("Updating evidence requirements for transaction: {}", request.getTransactionId());
        
        request.validate();
        
        EvidenceChecklist updatedChecklist = evidenceChecklistService.setCustomRequirements(
                request.getTransactionId(),
                request.isInvoiceRequiredSafe(),
                request.isShippingBillRequiredSafe(),
                request.isPackingListRequiredSafe(),
                request.isCertificateOriginRequiredSafe()
        );
        
        EvidenceChecklistResponse response = EvidenceChecklistResponse.fromEntity(updatedChecklist);
        return ResponseEntity.ok(ApiResponse.success(response, "Evidence requirements updated successfully"));
    }

    @Operation(summary = "Mark document as uploaded",
               description = "Updates checklist when a document is uploaded for a transaction")
    @PostMapping("/uploaded/{transactionId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceChecklistResponse>> markDocumentUploaded(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId,
            @Parameter(description = "Document type") @RequestParam DocumentType documentType) {
        
        log.info("Marking document {} as uploaded for transaction: {}", documentType, transactionId);
        
        EvidenceChecklist updatedChecklist = evidenceChecklistService.markDocumentUploaded(transactionId, documentType);
        EvidenceChecklistResponse response = EvidenceChecklistResponse.fromEntity(updatedChecklist);
        
        String message = updatedChecklist.isSatisfied() ? 
                "Document uploaded and evidence checklist satisfied" : 
                "Document uploaded successfully";
        
        return ResponseEntity.ok(ApiResponse.success(response, message));
    }

    @Operation(summary = "Check if evidence is complete",
               description = "Checks if all required documents have been uploaded for a transaction")
    @GetMapping("/complete/{transactionId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> isEvidenceComplete(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        boolean isComplete = evidenceChecklistService.isEvidenceComplete(transactionId);
        
        String message = isComplete ? 
                "Evidence collection is complete" : 
                "Evidence collection is pending";
        
        return ResponseEntity.ok(ApiResponse.success(isComplete, message));
    }

    @Operation(summary = "Re-evaluate evidence checklist",
               description = "Forces re-evaluation of evidence satisfaction status")
    @PostMapping("/reevaluate/{transactionId}")
    @PreAuthorize("hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceChecklistResponse>> reevaluateChecklist(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.info("Re-evaluating evidence checklist for transaction: {}", transactionId);
        
        EvidenceChecklist checklist = evidenceChecklistService.reevaluateChecklist(transactionId);
        EvidenceChecklistResponse response = EvidenceChecklistResponse.fromEntity(checklist);
        
        return ResponseEntity.ok(ApiResponse.success(response, "Evidence checklist re-evaluated successfully"));
    }

    @Operation(summary = "Get evidence summary for merchant",
               description = "Returns simplified evidence status for merchant dashboard")
    @GetMapping("/summary/{transactionId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('COMPLIANCE_OFFICER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EvidenceSummaryResponse>> getEvidenceSummary(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        List<DocumentType> missingTypes = evidenceChecklistService.getMissingDocuments(transactionId);
        boolean isComplete = evidenceChecklistService.isEvidenceComplete(transactionId);
        
        EvidenceSummaryResponse summary = EvidenceSummaryResponse.builder()
                .transactionId(transactionId)
                .evidenceComplete(isComplete)
                .missingDocumentCount(missingTypes.size())
                .nextRequiredDocument(missingTypes.isEmpty() ? null : missingTypes.get(0))
                .status(isComplete ? "COMPLETE" : "PENDING")
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(summary, "Evidence summary retrieved successfully"));
    }

    /**
     * Simple evidence summary response for dashboard use.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EvidenceSummaryResponse {
        private UUID transactionId;
        private boolean evidenceComplete;
        private int missingDocumentCount;
        private DocumentType nextRequiredDocument;
        private String status;
    }
}