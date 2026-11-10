package com.aegisx.integrity;

import com.aegisx.integrity.entity.EvidenceExtraction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceExtractionConfidenceKindTest {

    @Test
    void distinguishesHeuristicHumanConfirmedAndUnavailableConfidence() {
        assertEquals("HEURISTIC", extraction(EvidenceExtraction.Method.TIKA, false).getConfidenceKind());
        assertEquals("HEURISTIC", extraction(EvidenceExtraction.Method.TESSERACT, false).getConfidenceKind());
        assertEquals("HUMAN_CONFIRMED", extraction(EvidenceExtraction.Method.MANUAL_CORRECTION, true).getConfidenceKind());
        assertEquals("NOT_AVAILABLE", extraction(EvidenceExtraction.Method.NOT_ATTEMPTED, false).getConfidenceKind());
    }

    private EvidenceExtraction extraction(EvidenceExtraction.Method method, boolean corrected) {
        return EvidenceExtraction.builder()
                .extractionMethod(method)
                .humanCorrection(corrected)
                .build();
    }
}
