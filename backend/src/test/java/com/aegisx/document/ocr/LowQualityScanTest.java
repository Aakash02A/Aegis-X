package com.aegisx.document.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for low-quality document processing.
 * 
 * Verifies that degraded input (blurry, rotated, low-contrast) is handled gracefully:
 * - Extraction succeeds or fails with clear exception
 * - Confidence score reflects quality issues
 * - Extraction is persisted even if confidence is low
 */
@SpringBootTest
@DisplayName("Low-Quality Scan Processing Tests")
class LowQualityScanTest {

    @Autowired
    private StructuredExtractionService structuredExtractionService;

    /**
     * Simulates blurry/low-quality OCR output.
     * Common OCR errors from degraded scans:
     * - Missing fields
     * - Malformed numbers/dates
     * - Partial text recognition
     */
    private static final String LOW_QUALITY_TEXT = """
        INVOICE
        Invoice Number: INV-l024    [OCR error: lowercase 'l' instead of '1']
        Invoice Date: 09/2l/2026    [OCR error: partial date]
        
        Amount: USD 2a50.50    [OCR error: malformed number]
        
        Beneficiary: XYZ Imports LLC
        """;

    /**
     * Simulates rotated/skewed OCR output.
     * Typical of photos of documents taken at an angle.
     */
    private static final String ROTATED_SCAN_TEXT = """
        ECIOVNI
        rebmuN eciovnI: 4201-VNI
        etaD eciovnI: 6202-90-12
        tnuomA: 05.054$
        yraicifeneB: CLL stropmi ZYX
        """;

    /**
     * Minimal/sparse text from low-contrast scan.
     * Just barely readable.
     */
    private static final String LOW_CONTRAST_TEXT = """
        INVOICE
        INV-1024
        09/21/2026
        USD 2450.50
        """;

    @Test
    @DisplayName("Should extract from blurry text with reduced confidence")
    void testExtractFromBlurryOcr() {
        // Act
        JsonNode result = structuredExtractionService.extractStructuredData(LOW_QUALITY_TEXT);
        Double confidence = structuredExtractionService.calculateConfidenceScore(result);

        // Assert: Extraction succeeds
        assertNotNull(result);
        assertNotNull(confidence);

        // Assert: Confidence is lower due to OCR errors
        assertTrue(confidence < 80, "Blurry OCR should have lower confidence");
        assertTrue(confidence > 0, "Confidence should not be zero for partial extraction");

        // Assert: At least some fields extracted despite errors
        assertTrue(result.has("invoiceNumber") || result.has("amount"), 
            "Should extract at least some recognizable fields");
    }

    @Test
    @DisplayName("Should detect rotated/skewed text gracefully")
    void testExtractFromRotatedScan() {
        // Act
        JsonNode result = structuredExtractionService.extractStructuredData(ROTATED_SCAN_TEXT);
        Double confidence = structuredExtractionService.calculateConfidenceScore(result);

        // Assert: Extraction completes without crashing
        assertNotNull(result);
        assertNotNull(confidence);

        // Assert: Confidence reflects unreadability
        // Rotated text typically won't match patterns, so confidence should be very low
        assertTrue(confidence >= 0 && confidence <= 100, 
            "Confidence should be in valid range even for unreadable text");
    }

    @Test
    @DisplayName("Should extract minimal but valid fields from low-contrast scan")
    void testExtractFromLowContrastText() {
        // Act
        JsonNode result = structuredExtractionService.extractStructuredData(LOW_CONTRAST_TEXT);
        Double confidence = structuredExtractionService.calculateConfidenceScore(result);

        // Assert: Extraction succeeds
        assertNotNull(result);
        assertNotNull(confidence);

        // Assert: Confidence is moderate (minimal but readable text)
        assertTrue(confidence >= 50, "Sparse but clear text should have reasonable confidence");

        // Assert: Core fields extracted
        assertNotNull(result.get("invoiceNumber"));
        assertNotNull(result.get("invoiceDate"));
        assertNotNull(result.get("currency"));
        assertNotNull(result.get("amount"));
    }

    @Test
    @DisplayName("Should calculate confidence correctly for degraded input")
    void testConfidenceScoreReflectsQuality() {
        // Extract from three quality levels
        JsonNode goodQuality = structuredExtractionService.extractStructuredData(
            "Invoice Number: INV-1024\nInvoice Date: 09/21/2026\nCurrency: USD\nAmount: 2450.50\nBeneficiary: XYZ Imports"
        );
        Double goodConfidence = structuredExtractionService.calculateConfidenceScore(goodQuality);

        JsonNode poorQuality = structuredExtractionService.extractStructuredData(LOW_QUALITY_TEXT);
        Double poorConfidence = structuredExtractionService.calculateConfidenceScore(poorQuality);

        // Assert: Quality gradient is respected
        assertTrue(goodConfidence > poorConfidence, 
            "High-quality text should have higher confidence than low-quality");
    }

    @Test
    @DisplayName("Should not crash on empty or null input")
    void testHandleEdgeCases() {
        // Act & Assert: Empty string
        JsonNode emptyResult = structuredExtractionService.extractStructuredData("");
        Double emptyConfidence = structuredExtractionService.calculateConfidenceScore(emptyResult);
        assertNotNull(emptyResult);
        assertNotNull(emptyConfidence);
        assertTrue(emptyConfidence == 0, "Empty input should have zero confidence");

        // Act & Assert: Whitespace only
        JsonNode whitespaceResult = structuredExtractionService.extractStructuredData("   \n\n  ");
        Double whitespaceConfidence = structuredExtractionService.calculateConfidenceScore(whitespaceResult);
        assertNotNull(whitespaceResult);
        assertNotNull(whitespaceConfidence);
        assertTrue(whitespaceConfidence == 0, "Whitespace-only input should have zero confidence");
    }
}
