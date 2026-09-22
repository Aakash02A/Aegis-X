package com.aegisx.document.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StructuredExtractionService.
 * 
 * Tests:
 * - Invoice number extraction
 * - Date extraction
 * - Currency extraction
 * - Amount extraction
 * - Beneficiary extraction
 * - Confidence score calculation
 */
@SpringBootTest
@DisplayName("StructuredExtractionService Tests")
class StructuredExtractionServiceTest {

    @Autowired
    private StructuredExtractionService structuredExtractionService;

    private static final String SAMPLE_INVOICE = """
        INVOICE
        Invoice Number: INV-1024
        Invoice Date: 09/21/2026
        
        TO: XYZ Imports LLC
        
        AMOUNT: USD 2450.50
        
        Details:
        Item 1: 1000.00
        Item 2: 1450.50
        
        Total Due: USD 2450.50
        """;

    @Test
    @DisplayName("Should extract invoice number from standard format")
    void testExtractInvoiceNumber() {
        String text = "Invoice Number: INV-1024\nDetails...";

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("invoiceNumber"));
        assertEquals("INV-1024", result.get("invoiceNumber").asText());
        assertTrue(result.get("invoiceNumber_confidence").asInt() > 0);
    }

    @Test
    @DisplayName("Should extract invoice date from standard format")
    void testExtractInvoiceDate() {
        String text = SAMPLE_INVOICE;

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("invoiceDate"));
        assertEquals("2026-09-21", result.get("invoiceDate").asText());
        assertTrue(result.get("invoiceDate_confidence").asInt() > 0);
    }

    @Test
    @DisplayName("Should extract currency code")
    void testExtractCurrency() {
        String text = SAMPLE_INVOICE;

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("currency"));
        assertEquals("USD", result.get("currency").asText());
        assertTrue(result.get("currency_confidence").asInt() > 0);
    }

    @Test
    @DisplayName("Should extract invoice amount")
    void testExtractAmount() {
        String text = SAMPLE_INVOICE;

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("amount"));
        assertEquals(new BigDecimal("2450.50"), result.get("amount").decimalValue());
        assertTrue(result.get("amount_confidence").asInt() > 0);
    }

    @Test
    @DisplayName("Should extract beneficiary name")
    void testExtractBeneficiary() {
        String text = SAMPLE_INVOICE;

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("beneficiary"));
        assertTrue(result.get("beneficiary").asText().contains("XYZ"));
    }

    @Test
    @DisplayName("Should calculate overall confidence score")
    void testCalculateConfidenceScore() {
        String text = SAMPLE_INVOICE;

        JsonNode structuredData = structuredExtractionService.extractStructuredData(text);
        Double confidence = structuredExtractionService.calculateConfidenceScore(structuredData);

        assertNotNull(confidence);
        assertTrue(confidence >= 0 && confidence <= 100);
        assertTrue(confidence > 60, "Should have reasonable confidence for complete invoice");
    }

    @Test
    @DisplayName("Should handle invoice number variants (INV/2026/09/001)")
    void testExtractInvoiceNumberVariant() {
        String text = "Invoice: INV/2026/09/001\nDetails...";

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("invoiceNumber"));
        assertEquals("INV/2026/09/001", result.get("invoiceNumber").asText());
    }

    @Test
    @DisplayName("Should extract amount with comma separator")
    void testExtractAmountWithComma() {
        String text = "Total Amount: USD 2,450.50";

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("amount"));
        assertEquals(new BigDecimal("2450.50"), result.get("amount").decimalValue());
    }

    @Test
    @DisplayName("Should handle dates in different formats")
    void testExtractDateVariants() {
        String[] dateVariants = {
            "Invoice Date: 2026-09-21",
            "Invoice Date: 21-09-2026",
            "Invoice Date: 09/21/2026",
        };

        for (String text : dateVariants) {
            JsonNode result = structuredExtractionService.extractStructuredData(text);
            assertNotNull(result.get("invoiceDate"), "Should extract date from: " + text);
        }
    }

    @Test
    @DisplayName("Should return zero confidence for missing fields")
    void testConfidenceForMissingFields() {
        String emptyText = "Some random text with no invoice data";

        JsonNode result = structuredExtractionService.extractStructuredData(emptyText);
        Double confidence = structuredExtractionService.calculateConfidenceScore(result);

        assertNotNull(confidence);
        assertTrue(confidence < 50, "Should have low confidence for incomplete extraction");
    }

    @Test
    @DisplayName("Should handle null input gracefully")
    void testExtractFromNullInput() {
        assertThrows(NullPointerException.class, () -> {
            structuredExtractionService.extractStructuredData(null);
        });
    }

    @Test
    @DisplayName("Should extract EUR currency")
    void testExtractEuroCurrency() {
        String text = "Invoice Amount: EUR 1500.00";

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("currency"));
        assertEquals("EUR", result.get("currency").asText());
    }

    @Test
    @DisplayName("Should extract INR currency")
    void testExtractIndianRupeeCurrency() {
        String text = "Amount Due: INR 150000.00";

        JsonNode result = structuredExtractionService.extractStructuredData(text);

        assertNotNull(result.get("currency"));
        assertEquals("INR", result.get("currency").asText());
    }
}
