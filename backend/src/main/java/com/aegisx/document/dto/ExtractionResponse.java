package com.aegisx.document.dto;

import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST response for document extraction queries.
 * 
 * Contains the extracted structured data and confidence metrics for compliance decisions.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionResponse {

    /**
     * The document UUID
     */
    private UUID documentId;

    /**
     * The extraction record UUID
     */
    private UUID extractionId;

    /**
     * Current document processing status (OCR_COMPLETED, OCR_FAILED, etc.)
     */
    private DocumentStatus status;

    /**
     * Confidence score (0–100) for the extraction
     */
    private Double confidence;

    /**
     * The extracted structured data (invoice number, date, amount, etc.)
     */
    private JsonNode extractedData;

    /**
     * Extraction method used (TIKA or TESSERACT)
     */
    private String extractionMethod;

    /**
     * When the extraction was created
     */
    private Instant createdAt;

    /**
     * Build ExtractionResponse from a DocumentExtraction entity.
     */
    public static ExtractionResponse fromEntity(DocumentExtraction extraction) {
        return ExtractionResponse.builder()
            .documentId(extraction.getDocument().getId())
            .extractionId(extraction.getId())
            .status(extraction.getDocument().getDocumentStatus())
            .confidence(extraction.getConfidenceScore())
            .extractedData(extraction.getExtractedData())
            .extractionMethod(extraction.getExtractionMethod().toString())
            .createdAt(extraction.getCreatedAt())
            .build();
    }
}
