package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Module I: one extraction attempt against an evidence document. Append-only.
 *
 * <p>A human correction is stored as a new row with method {@link Method#MANUAL_CORRECTION} rather
 * than by overwriting the machine's output. That is what lets the console answer "what did OCR
 * actually read?" separately from "what does the business say the invoice total is?" — and it means
 * a correction cannot hide what it corrected.</p>
 *
 * <p>{@code uncertainFields} and {@code absentFields} are persisted rather than recomputed, so the
 * set of values a human must check is a property of the extraction, decided when it ran, and does
 * not drift when a threshold is later tuned.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_evidence_extractions")
public class EvidenceExtraction extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_method", nullable = false, length = 30)
    private Method extractionMethod;

    /** Raw text, retained for debugging and for re-matching without re-reading the file. */
    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    /** JSON object of the fields that were read. */
    @Column(name = "extracted_data", columnDefinition = "TEXT")
    private String extractedData;

    /** JSON array of field names that were read but not confidently. */
    @Column(name = "uncertain_fields", columnDefinition = "TEXT")
    private String uncertainFields;

    /** JSON array of field names that were required and not found at all. */
    @Column(name = "absent_fields", columnDefinition = "TEXT")
    private String absentFields;

    @Column(name = "overall_confidence")
    private Double overallConfidence;

    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;

    @Column(name = "ocr_language", length = 10)
    private String ocrLanguage;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "actor_label", nullable = false, length = 120)
    private String actorLabel;

    @Builder.Default
    @Column(name = "human_correction", nullable = false)
    private boolean humanCorrection = false;

    /** How the values were produced. */
    public enum Method {
        /** Native text layer read from the file. */
        TIKA,
        /** Optical character recognition, used when there is no usable text layer. */
        TESSERACT,
        /** Supplied or replaced by a person. */
        MANUAL_CORRECTION,
        /** Recorded when an attempt could not be made, for example when Tesseract is absent. */
        NOT_ATTEMPTED
    }

    /** The confidence values are pattern-match heuristics, except when a person supplied a correction. */
    @Transient
    @JsonProperty("confidenceKind")
    public String getConfidenceKind() {
        if (humanCorrection || extractionMethod == Method.MANUAL_CORRECTION) {
            return "HUMAN_CONFIRMED";
        }
        if (extractionMethod == null || extractionMethod == Method.NOT_ATTEMPTED) {
            return "NOT_AVAILABLE";
        }
        return "HEURISTIC";
    }
}
