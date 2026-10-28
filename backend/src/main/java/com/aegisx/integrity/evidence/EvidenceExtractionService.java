package com.aegisx.integrity.evidence;

import com.aegisx.document.ocr.TesseractOcrProcessor;
import com.aegisx.document.ocr.TikaTextExtractor;
import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.EvidenceExtraction;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.repository.EvidenceDocumentRepository;
import com.aegisx.integrity.repository.EvidenceExtractionRepository;
import com.aegisx.integrity.security.ActorContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Module I: reading an evidence document, and letting a person overrule the reading.
 *
 * <p>The pipeline is the project's existing one — Apache Tika for the text layer, Tesseract for
 * images and scanned pages when Tika finds too little — so evidence documents are read exactly the
 * way the compliance module reads its documents. Only the field interpretation differs (see
 * {@link EvidenceFieldExtractor}).</p>
 *
 * <p>A failure is <strong>recorded, not thrown away</strong>. The document is left in
 * {@code FAILED} with a plain-language reason and the attempt is written to the extraction history,
 * because "we could not read this document" and "this document is not required" lead to different
 * follow-up and must not collapse into the same absent state.</p>
 *
 * <p>A correction is stored as a new extraction row with method {@code MANUAL_CORRECTION}. The
 * machine's reading is never edited, so the console can always show what was read and what a person
 * changed it to — and a correction cannot quietly rewrite history.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceExtractionService {

    /** Outcome of an extraction attempt. A failure is a result, not an exception. */
    public record Outcome(EvidenceExtraction extraction, boolean failed, String message) { }

    private final EvidenceDocumentRepository documentRepository;
    private final EvidenceExtractionRepository extractionRepository;
    private final EvidenceDocumentService documentService;
    private final TikaTextExtractor tikaTextExtractor;
    private final TesseractOcrProcessor tesseractOcrProcessor;
    private final EvidenceFieldExtractor fieldExtractor;
    private final IntegrityAuditService auditService;
    private final IntegrityProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public Outcome extract(UUID workspaceId, UUID documentId, boolean simulated) {
        EvidenceDocument document = documentService.require(workspaceId, documentId);
        document.setProcessingStatus(EvidenceProcessingStatus.PROCESSING);
        documentRepository.save(document);

        long startedAt = System.currentTimeMillis();
        byte[] bytes;
        try {
            Resource resource = documentService.load(workspaceId, documentId);
            try (InputStream stream = resource.getInputStream()) {
                bytes = stream.readAllBytes();
            }
        } catch (IOException | RuntimeException e) {
            return fail(workspaceId, document, "The stored file could not be opened: " + e.getMessage(),
                    EvidenceExtraction.Method.NOT_ATTEMPTED, startedAt, simulated);
        }

        String rawText;
        EvidenceExtraction.Method method = EvidenceExtraction.Method.TIKA;
        try {
            rawText = tikaTextExtractor.extractText(new ByteArrayInputStream(bytes));
            if (!tikaTextExtractor.isSufficientText(rawText)) {
                method = EvidenceExtraction.Method.TESSERACT;
                String mimeType = document.getMimeType();
                if (mimeType != null && mimeType.startsWith("image/")) {
                    rawText = tesseractOcrProcessor.processImage(new ByteArrayInputStream(bytes), mimeType);
                } else if ("application/pdf".equalsIgnoreCase(mimeType)) {
                    rawText = tesseractOcrProcessor.processPdf(new ByteArrayInputStream(bytes));
                } else {
                    rawText = null;
                }
            }
        } catch (RuntimeException e) {
            // Tesseract not installed, an unreadable image, a corrupt PDF: all the same to the operator.
            return fail(workspaceId, document, "The document could not be read: " + e.getMessage(),
                    EvidenceExtraction.Method.TESSERACT, startedAt, simulated);
        }

        if (rawText == null || rawText.isBlank()) {
            return fail(workspaceId, document,
                    "No legible text was found. The document may be a scan without a text layer, or an image "
                            + "format the reader cannot interpret.",
                    method, startedAt, simulated);
        }

        double threshold = properties.getEvidence().getConfidenceThreshold();
        EvidenceFieldExtractor.Result fields = fieldExtractor.extract(rawText, threshold);
        long duration = System.currentTimeMillis() - startedAt;

        EvidenceExtraction extraction = extractionRepository.save(EvidenceExtraction.builder()
                .workspaceId(workspaceId)
                .documentId(document.getId())
                .extractionMethod(method)
                .rawText(rawText)
                .extractedData(toJson(fields.values()))
                .uncertainFields(toJson(fields.uncertainFields()))
                .absentFields(toJson(fields.absentFields()))
                .overallConfidence(fields.overallConfidence())
                .processingDurationMs(duration)
                .ocrLanguage("eng")
                .actorLabel("SYSTEM")
                .humanCorrection(false)
                .build());

        // The document was read, but a person has not confirmed the values yet, so the confirmation
        // flag is reset. A superseded predecessor's confirmation must not be inherited by a new file.
        applyFields(document, fields);
        document.setFieldsConfirmed(false);
        document.setProcessingStatus(EvidenceProcessingStatus.EXTRACTED);
        document.setProcessingError(null);
        documentRepository.save(document);

        audit(workspaceId, document, "EVIDENCE_EXTRACTION_COMPLETED", "SYSTEM", null, simulated, Map.of(
                "method", method.name(),
                "confidence", fields.overallConfidence(),
                "uncertainFields", String.join(",", fields.uncertainFields()),
                "absentFields", String.join(",", fields.absentFields()),
                "processingDurationMs", duration));

        log.info("Extracted evidence document {} with method {} confidence {}; uncertain={} absent={}",
                document.getId(), method, fields.overallConfidence(), fields.uncertainFields(),
                fields.absentFields());
        return new Outcome(extraction, false, "Extraction completed");
    }

    /**
     * Records a human correction of one field and refreshes the document's working values.
     *
     * <p>Field names are the API's, not Java's, and are validated: a typo must fail loudly rather
     * than create an extraction that appears to correct nothing. {@code totalMinor} takes integer
     * minor units, which removes any ambiguity about decimal places.</p>
     */
    @Transactional
    public Outcome correct(UUID workspaceId, UUID documentId, String field, String value,
                           ActorContext.Actor actor, boolean simulated) {
        EvidenceDocument document = documentService.require(workspaceId, documentId);
        if (!document.isActive()) {
            throw new IntegrityConflictException(
                    "This document was superseded by a newer one; correct the current document instead");
        }
        if (field == null || field.isBlank()) {
            throw new IntegrityConflictException("A field name is required");
        }
        if (value == null || value.isBlank()) {
            throw new IntegrityConflictException("A value is required; clear a field by supplying a corrected one");
        }

        Map<String, Object> correction = new LinkedHashMap<>();
        switch (field) {
            case EvidenceFieldExtractor.FIELD_INVOICE_NUMBER ->
                    document.setExtractedInvoiceNumber(trimTo(value, 120, field));
            case EvidenceFieldExtractor.FIELD_INVOICE_DATE -> {
                try {
                    document.setExtractedInvoiceDate(LocalDate.parse(value.trim()));
                } catch (RuntimeException e) {
                    throw new IntegrityConflictException(
                            "invoiceDate must be an ISO date such as 2026-10-11");
                }
            }
            case "totalMinor" -> document.setExtractedAmountMinor(parseMinorUnits(value));
            case EvidenceFieldExtractor.FIELD_CURRENCY -> document.setExtractedCurrency(parseCurrency(value));
            case EvidenceFieldExtractor.FIELD_ORDER_REFERENCE ->
                    document.setExtractedOrderReference(trimTo(value, 120, field));
            case EvidenceFieldExtractor.FIELD_PAYMENT_REFERENCE ->
                    document.setExtractedPaymentReference(trimTo(value, 120, field));
            default -> throw new IntegrityConflictException(
                    "Unknown field '" + field + "'. Correctable fields: invoiceNumber, invoiceDate, totalMinor, "
                            + "currency, orderReference, paymentReference");
        }
        correction.put(field, value.trim());

        long startedAt = System.currentTimeMillis();
        EvidenceExtraction extraction = extractionRepository.save(EvidenceExtraction.builder()
                .workspaceId(workspaceId)
                .documentId(document.getId())
                .extractionMethod(EvidenceExtraction.Method.MANUAL_CORRECTION)
                .extractedData(toJson(correction))
                .uncertainFields("[]")
                .absentFields("[]")
                .overallConfidence(100.0)
                .processingDurationMs(System.currentTimeMillis() - startedAt)
                .actorLabel(actor == null ? "UNKNOWN" : actor.label())
                .rawText(null)
                .humanCorrection(true)
                .build());

        document.setFieldsConfirmed(true);
        document.setConfirmedBy(actor == null ? null : actor.userId());
        document.setConfirmedAt(java.time.Instant.now());
        document.setProcessingStatus(EvidenceProcessingStatus.EXTRACTED);
        document.setProcessingError(null);
        documentRepository.save(document);

        audit(workspaceId, document, "EVIDENCE_FIELD_CORRECTED",
                actor == null ? "UNKNOWN" : actor.label(), actor == null ? null : actor.userId(), simulated,
                Map.of("field", field, "extractionId", extraction.getId().toString()));

        log.info("Evidence document {} field {} corrected by {}", documentId, field,
                actor == null ? "UNKNOWN" : actor.label());
        return new Outcome(extraction, false, "Value confirmed and recorded as a correction");
    }

    @Transactional(readOnly = true)
    public List<EvidenceExtraction> history(UUID workspaceId, UUID documentId) {
        documentService.require(workspaceId, documentId);
        return extractionRepository.findByDocumentIdOrderByCreatedAtAsc(documentId);
    }

    @Transactional(readOnly = true)
    public Optional<EvidenceExtraction> latest(UUID workspaceId, UUID documentId) {
        documentService.require(workspaceId, documentId);
        return extractionRepository.findByDocumentIdOrderByCreatedAtAsc(documentId).stream()
                .reduce((first, second) -> second);
    }

    /** Uncertain and absent fields of the newest extraction, for the review panel. */
    @Transactional(readOnly = true)
    public List<String> uncertainFields(EvidenceDocument document) {
        return extractionRepository.findByDocumentIdOrderByCreatedAtAsc(document.getId()).stream()
                .reduce((first, second) -> second)
                .map(extraction -> parseJsonArray(extraction.getUncertainFields()))
                .orElseGet(List::of);
    }

    // ------------------------------------------------------------------ helpers

    private Outcome fail(UUID workspaceId, EvidenceDocument document, String reason,
                         EvidenceExtraction.Method method, long startedAt, boolean simulated) {
        extractionRepository.save(EvidenceExtraction.builder()
                .workspaceId(workspaceId)
                .documentId(document.getId())
                .extractionMethod(method)
                .failureReason(trimTo(reason, 500, "failureReason"))
                .uncertainFields("[]")
                .absentFields("[]")
                .processingDurationMs(System.currentTimeMillis() - startedAt)
                .actorLabel("SYSTEM")
                .humanCorrection(false)
                .build());

        document.setProcessingStatus(EvidenceProcessingStatus.FAILED);
        document.setProcessingError(trimTo(reason, 500, "failureReason"));
        document.setFieldsConfirmed(false);
        documentRepository.save(document);

        audit(workspaceId, document, "EVIDENCE_EXTRACTION_FAILED", "SYSTEM", null, simulated,
                Map.of("method", method.name(), "reason", trimTo(reason, 400, "reason")));
        log.warn("Evidence extraction failed for document {}: {}", document.getId(), reason);
        return new Outcome(null, true, reason);
    }

    private void applyFields(EvidenceDocument document, EvidenceFieldExtractor.Result fields) {
        document.setExtractedInvoiceNumber(fields.stringValue(EvidenceFieldExtractor.FIELD_INVOICE_NUMBER)
                .map(value -> trimTo(value, 120, "invoiceNumber")).orElse(null));
        document.setExtractedInvoiceDate(fields.stringValue(EvidenceFieldExtractor.FIELD_INVOICE_DATE)
                .map(value -> {
                    try {
                        return LocalDate.parse(value);
                    } catch (RuntimeException e) {
                        return null;
                    }
                }).orElse(null));
        document.setExtractedAmountMinor(fields.amountMinor(EvidenceFieldExtractor.FIELD_TOTAL).orElse(null));
        document.setExtractedCurrency(fields.stringValue(EvidenceFieldExtractor.FIELD_CURRENCY).orElse(null));
        document.setExtractedOrderReference(fields.stringValue(EvidenceFieldExtractor.FIELD_ORDER_REFERENCE)
                .map(value -> trimTo(value, 120, "orderReference")).orElse(null));
        document.setExtractedPaymentReference(fields.stringValue(EvidenceFieldExtractor.FIELD_PAYMENT_REFERENCE)
                .map(value -> trimTo(value, 120, "paymentReference")).orElse(null));
    }

    private static long parseMinorUnits(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IntegrityConflictException(
                    "totalMinor must be a non-negative whole number of minor units, for example 150000 for 1500.00");
        }
    }

    private static String parseCurrency(String value) {
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (upper.length() != 3) {
            throw new IntegrityConflictException("currency must be a three-letter ISO code, for example INR");
        }
        try {
            java.util.Currency.getInstance(upper);
        } catch (IllegalArgumentException e) {
            throw new IntegrityConflictException("'" + upper + "' is not a recognised ISO currency code");
        }
        return upper;
    }

    private static String trimTo(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IntegrityConflictException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
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

    private void audit(UUID workspaceId, EvidenceDocument document, String eventType, String actorLabel,
                       UUID actorId, boolean simulated, Map<String, Object> detail) {
        auditService.record(eventType, "EVIDENCE", actorLabel, workspaceId, detail,
                document.getOrderId(), document.getPaymentId(), null, null, null, null, actorId, simulated);
    }
}
