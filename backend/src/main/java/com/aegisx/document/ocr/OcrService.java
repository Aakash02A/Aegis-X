package com.aegisx.document.ocr;

import com.aegisx.common.exception.OcrProcessingException;
import com.aegisx.common.exception.UnsupportedDocumentException;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.storage.service.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * High-level OCR service facade.
 * 
 * Coordinates document loading, storage access, OCR processing, and persistence.
 * Handles transaction boundaries and error recovery.
 * 
 * Public API for OCR operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    private final DocumentRepository documentRepository;
    private final DocumentExtractionRepository extractionRepository;
    private final StorageService storageService;
    private final OcrProcessingCoordinator ocrProcessingCoordinator;

    /**
     * Initiate OCR processing for a document.
     * 
     * @param documentId the UUID of the document to process
     * @return the resulting DocumentExtraction with structured data
     * @throws IllegalArgumentException if document not found
     * @throws OcrProcessingException if OCR fails
     * @throws UnsupportedDocumentException if document format is unsupported
     */
    public DocumentExtraction processDocument(UUID documentId) {
        log.info("OCR service: processing document {}", documentId);

        // Load document from database
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        log.debug("Document loaded: {} (mime type: {})", documentId, document.getMimeType());

        // Load file stream from storage
        InputStream fileStream;
        try {
            Resource resource = storageService.load(document.getStoragePath());
            fileStream = resource.getInputStream();
            log.debug("File stream loaded from storage");
        } catch (IOException e) {
            log.error("Failed to load document file from storage", e);
            throw new OcrProcessingException("Failed to load document from storage", e);
        } catch (Exception e) {
            log.error("Failed to load document file from storage", e);
            throw new OcrProcessingException("Failed to load document from storage", e);
        }

        // Process through OCR pipeline
        try {
            DocumentExtraction extraction = ocrProcessingCoordinator.processDocument(document, fileStream);
            log.info("OCR processing completed successfully for document {}", documentId);
            return extraction;
        } finally {
            // Clean up stream
            try {
                fileStream.close();
            } catch (Exception e) {
                log.warn("Failed to close file stream", e);
            }
        }
    }

    /**
     * Retrieve the latest extraction for a document.
     * 
     * @param documentId the document UUID
     * @return the most recent DocumentExtraction, or null if none exists
     */
    public DocumentExtraction getLatestExtraction(UUID documentId) {
        log.debug("Retrieving latest extraction for document {}", documentId);
        return extractionRepository.findLatestByDocumentId(documentId).orElse(null);
    }

    /**
     * Retrieve extraction by ID.
     * 
     * @param extractionId the extraction UUID
     * @return the DocumentExtraction, or null if not found
     */
    public DocumentExtraction getExtraction(UUID extractionId) {
        return extractionRepository.findById(extractionId).orElse(null);
    }
}
