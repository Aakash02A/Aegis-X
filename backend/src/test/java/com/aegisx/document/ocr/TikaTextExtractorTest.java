package com.aegisx.document.ocr;

import com.aegisx.common.exception.OcrProcessingException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TikaTextExtractor.
 * 
 * Tests:
 * - Embedded text extraction from PDFs
 * - Text extraction from images
 * - Sufficient text detection heuristic
 */
@SpringBootTest
@DisplayName("TikaTextExtractor Tests")
class TikaTextExtractorTest {

    @Autowired
    private TikaTextExtractor tikaTextExtractor;

    @Test
    @DisplayName("Should extract text from plain text stream")
    void testExtractTextFromPlainText() {
        String sampleText = "Invoice Number: INV-1024\nAmount: USD 2450.50\nDate: 2026-09-21\nBeneficiary: XYZ Imports LLC";
        InputStream inputStream = new ByteArrayInputStream(sampleText.getBytes());

        String result = tikaTextExtractor.extractText(inputStream);

        assertNotNull(result);
        assertTrue(result.length() > 0);
        assertTrue(result.contains("Invoice"));
    }

    @Test
    @DisplayName("Should handle empty input stream gracefully")
    void testExtractTextFromEmptyStream() {
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);

        String result = tikaTextExtractor.extractText(inputStream);

        assertNotNull(result);
        assertEquals("", result);
    }

    @Test
    @DisplayName("Should detect sufficient text (>= 500 chars)")
    void testIsSufficientTextWithLongText() {
        String longText = "a".repeat(600);

        boolean result = tikaTextExtractor.isSufficientText(longText);

        assertTrue(result);
    }

    @Test
    @DisplayName("Should detect insufficient text (< 500 chars)")
    void testIsSufficientTextWithShortText() {
        String shortText = "a".repeat(100);

        boolean result = tikaTextExtractor.isSufficientText(shortText);

        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false for null text")
    void testIsSufficientTextWithNullText() {
        boolean result = tikaTextExtractor.isSufficientText(null);

        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false for empty text")
    void testIsSufficientTextWithEmptyText() {
        boolean result = tikaTextExtractor.isSufficientText("");

        assertFalse(result);
    }

    @Test
    @DisplayName("Should handle boundary case at 500 chars")
    void testIsSufficientTextAtBoundary() {
        String boundaryText = "a".repeat(500);

        boolean result = tikaTextExtractor.isSufficientText(boundaryText);

        assertTrue(result);
    }

    @Test
    @DisplayName("Should handle boundary case at 499 chars")
    void testIsSufficientTextJustBelowBoundary() {
        String boundaryText = "a".repeat(499);

        boolean result = tikaTextExtractor.isSufficientText(boundaryText);

        assertFalse(result);
    }
}
