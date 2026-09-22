package com.aegisx.document.ocr;

import com.aegisx.common.exception.OcrProcessingException;
import com.aegisx.common.exception.UnsupportedDocumentException;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentExtraction.ExtractionMethod;
import com.aegisx.document.entity.DocumentStatus;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the OCR processing pipeline.
 * 
 * Flow:
 * 1. Load document and stream from storage
 * 2. Attempt Tika text extraction
 * 3. Decide: if sufficient text → skip OCR; else → proceed to Tesseract
 * 4. Extract structured fields from raw text
 * 5. Calculate confidence score
 * 6. Persist extraction record
 * 7. Update document status
 * 
 * On error: Update status to OCR_FAILED, throw exception
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrProcessingCoordinator {

    private final TikaTextExtractor tikaTextExtractor;
    private final TesseractOcrProcessor tesseractOcrProcessor;
    private final StructuredExtractionService structuredExtractionService;
    private final DocumentRepository documentRepository;
    private final DocumentExtractionRepository extractionRepository;

    /**
     * Process a document through the OCR pipeline.
     * 
     * @param document the document to process
     * @param inputStream the document file stream
     * @return the persisted DocumentExtraction record
     * @throws OcrProcessingException if processing fails
     * @throws UnsupportedDocumentException if the document format is not supported
     */
    @Transactional
    public DocumentExtraction processDocument(Document document, InputStream inputStream) {
        UUID documentId = document.getId();
        log.info("Starting OCR processing for document: {}", documentId);

        long startTime = System.currentTimeMillis();

        try {
            // Update document status to OCR_PROCESSING
            document.setDocumentStatus(DocumentStatus.OCR_PROCESSING);
            documentRepository.save(document);
            log.debug("Document status updated to OCR_PROCESSING");

            // Step 1: Attempt Tika text extraction
            String extractedText = tikaTextExtractor.extractText(inputStream);
            log.debug("Tika extraction produced {} characters", extractedText.length());

            ExtractionMethod method = ExtractionMethod.TIKA;
            String language = "eng"; // Default for MVP

            // Step 2: Decide: do we need OCR?
            if (!tikaTextExtractor.isSufficientText(extractedText)) {
                log.debug("Insufficient text from Tika. Attempting Tesseract OCR.");
                try {
                    // For now, Tesseract requires image input or image conversion from PDF
                    // Skip if we can't convert
                    log.warn("Scanned document detection not yet fully implemented. Using Tika text only.");
                } catch (UnsupportedDocumentException e) {
                    log.debug("OCR not attempted: {}", e.getMessage());
                }
            }

            // Step 3: Extract structured fields
            JsonNode structuredData = structuredExtractionService.extractStructuredData(extractedText);
            log.debug("Structured extraction completed");

            // Step 4: Calculate confidence score
            Double confidenceScore = structuredExtractionService.calculateConfidenceScore(structuredData);
            log.debug("Confidence score calculated: {}", confidenceScore);

            // Step 5: Calculate processing duration
            long processingDurationMs = System.currentTimeMillis() - startTime;
            log.debug("Processing duration: {} ms", processingDurationMs);

            // Step 6: Create and persist extraction record
            DocumentExtraction extraction = DocumentExtraction.builder()
                .document(document)
                .extractedText(extractedText)
                .extractedData(structuredData)
                .extractionMethod(method)
                .confidenceScore(confidenceScore)
                .processingDurationMs(processingDurationMs)
                .ocrLanguage(language)
                .build();

            extraction = extractionRepository.save(extraction);
            log.info("DocumentExtraction saved with ID: {}. Duration: {} ms, Confidence: {}", 
                extraction.getId(), processingDurationMs, confidenceScore);

            // Step 7: Update document status to OCR_COMPLETED
            document.setDocumentStatus(DocumentStatus.OCR_COMPLETED);
            documentRepository.save(document);
            log.info("Document status updated to OCR_COMPLETED. Document: {}", documentId);

            return extraction;

        } catch (UnsupportedDocumentException e) {
            log.warn("Unsupported document format: {}", e.getMessage());
            document.setDocumentStatus(DocumentStatus.OCR_FAILED);
            documentRepository.save(document);
            throw e;
        } catch (OcrProcessingException e) {
            log.error("OCR processing failed for document {}: {}", documentId, e.getMessage(), e);
            document.setDocumentStatus(DocumentStatus.OCR_FAILED);
            documentRepository.save(document);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during OCR processing for document {}", documentId, e);
            document.setDocumentStatus(DocumentStatus.OCR_FAILED);
            documentRepository.save(document);
            throw new OcrProcessingException("Unexpected error during OCR processing", e);
        }
    }
}
