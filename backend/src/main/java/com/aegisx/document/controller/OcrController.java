package com.aegisx.document.controller;

import com.aegisx.document.dto.ExtractionResponse;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.ocr.OcrService;
import com.aegisx.document.repository.DocumentRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for OCR operations.
 * 
 * Endpoints:
 * - POST /api/documents/{id}/process - trigger OCR processing
 * - GET /api/documents/{id}/extraction - retrieve extraction results
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService ocrService;
    private final DocumentRepository documentRepository;

    /**
     * Trigger OCR processing for a document.
     * 
     * @param documentId the document UUID
     * @param authentication the authenticated user
     * @return 202 ACCEPTED with processing status
     */
    @PostMapping("/{id}/process")
    public ResponseEntity<OcrProcessingResponse> processDocument(
        @PathVariable("id") UUID documentId,
        Authentication authentication
    ) {
        log.info("OCR process request for document: {}", documentId);

        // Load document and verify ownership
        Document document = documentRepository.findById(documentId)
            .orElse(null);

        if (document == null) {
            log.warn("Document not found: {}", documentId);
            return ResponseEntity.notFound().build();
        }

        // Verify merchant owns this document
        if (!document.getUploadedBy().getId().equals(
            UUID.fromString(authentication.getName())
        )) {
            log.warn("Unauthorized access to document: {} by user: {}", 
                documentId, authentication.getName());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            // Trigger OCR processing synchronously
            DocumentExtraction extraction = ocrService.processDocument(documentId);
            
            log.info("OCR processing completed for document: {}", documentId);

            return ResponseEntity.accepted().body(
                OcrProcessingResponse.builder()
                    .documentId(documentId)
                    .status(document.getDocumentStatus().toString())
                    .extractionId(extraction.getId())
                    .message("OCR processing completed")
                    .build()
            );
        } catch (Exception e) {
            log.error("OCR processing failed for document: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                OcrProcessingResponse.builder()
                    .documentId(documentId)
                    .status("OCR_FAILED")
                    .message("OCR processing failed: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * Retrieve extraction results for a document.
     * 
     * @param documentId the document UUID
     * @param authentication the authenticated user
     * @return extraction data with confidence and structured fields
     */
    @GetMapping("/{id}/extraction")
    public ResponseEntity<ExtractionResponse> getExtraction(
        @PathVariable("id") UUID documentId,
        Authentication authentication
    ) {
        log.info("Extraction query for document: {}", documentId);

        // Load document and verify ownership
        Document document = documentRepository.findById(documentId)
            .orElse(null);

        if (document == null) {
            log.warn("Document not found: {}", documentId);
            return ResponseEntity.notFound().build();
        }

        // Verify merchant owns this document
        if (!document.getUploadedBy().getId().equals(
            UUID.fromString(authentication.getName())
        )) {
            log.warn("Unauthorized access to document: {} by user: {}", 
                documentId, authentication.getName());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Retrieve latest extraction
        DocumentExtraction extraction = ocrService.getLatestExtraction(documentId);
        if (extraction == null) {
            log.debug("No extraction found for document: {}", documentId);
            return ResponseEntity.notFound().build();
        }

        ExtractionResponse response = ExtractionResponse.fromEntity(extraction);
        return ResponseEntity.ok(response);
    }

    /**
     * Response DTO for OCR processing endpoint.
     */
    @lombok.Getter
    @lombok.Setter
    @lombok.Builder
    public static class OcrProcessingResponse {
        private UUID documentId;
        private String status;
        private UUID extractionId;
        private String message;
    }
}
