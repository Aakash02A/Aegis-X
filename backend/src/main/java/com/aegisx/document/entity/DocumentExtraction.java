package com.aegisx.document.entity;

import com.aegisx.common.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * DocumentExtraction represents OCR output and structured extraction results.
 * 
 * This entity separates raw extraction from future AI analysis, allowing:
 * - Multiple extraction attempts (versioning)
 * - Comparison of extraction methods
 * - Confidence tracking for compliance decisions
 * 
 * Never overwritten; new extractions create new records.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "document_extractions")
public class DocumentExtraction extends BaseEntity {

    /**
     * Reference to the Document being extracted.
     * CASCADE delete ensures extraction records are cleaned up with documents.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    /**
     * Raw text extracted from the document.
     * May be embedded text (from Tika) or OCR'd text (from Tesseract).
     * Stored as-is for debugging and audit purposes.
     */
    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    /**
     * Structured extraction in JSON format.
     * Example:
     * {
     *   "invoiceNumber": "INV-1024",
     *   "invoiceDate": "2026-09-21",
     *   "currency": "USD",
     *   "amount": 2450.50,
     *   "beneficiary": "XYZ Imports LLC"
     * }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_data", columnDefinition = "jsonb")
    private JsonNode extractedData;

    /**
     * How the text was extracted.
     * Values: "TIKA", "TESSERACT"
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_method", nullable = false, length = 50)
    private ExtractionMethod extractionMethod;

    /**
     * Confidence score (0–100) for the extraction.
     * Heuristically computed based on field matches and pattern recognition.
     * Higher = more reliable for compliance decisions.
     */
    @Column(name = "confidence_score")
    private Double confidenceScore;

    /**
     * How long OCR processing took (milliseconds).
     * Used for performance monitoring and optimization tracking.
     */
    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;

    /**
     * Language(s) used for OCR processing.
     * Examples: "eng", "eng+hin", "eng+fra"
     * Future support for multi-language extraction.
     */
    @Column(name = "ocr_language", length = 10)
    private String ocrLanguage;

    /**
     * Extraction method enumeration.
     */
    public enum ExtractionMethod {
        TIKA,      // Native text from PDF or embedded in image
        TESSERACT  // OCR'd text from scanned documents
    }
}
