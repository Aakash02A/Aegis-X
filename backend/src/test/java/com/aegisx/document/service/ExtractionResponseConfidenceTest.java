package com.aegisx.document.service;

import com.aegisx.document.dto.ExtractionResponse;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtractionResponseConfidenceTest {

    @Test
    void labelsPatternBasedExtractionConfidenceAsHeuristic() {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setDocumentStatus(DocumentStatus.OCR_COMPLETED);

        DocumentExtraction extraction = new DocumentExtraction();
        extraction.setId(UUID.randomUUID());
        extraction.setDocument(document);
        extraction.setExtractionMethod(DocumentExtraction.ExtractionMethod.TIKA);
        extraction.setConfidenceScore(95.0);

        ExtractionResponse response = ExtractionResponse.fromEntity(extraction);

        assertEquals("HEURISTIC", response.getConfidenceKind());
        assertEquals(95.0, response.getConfidence());
    }
}
