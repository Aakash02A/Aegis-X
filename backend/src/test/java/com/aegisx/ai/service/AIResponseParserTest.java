package com.aegisx.ai.service;

import com.aegisx.ai.dto.AIAnalysisResponse;
import com.aegisx.common.exception.InvalidAIResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AIResponseParser Unit Tests")
class AIResponseParserTest {
    
    private AIResponseParser parser;
    private ConfidenceNormalizer normalizer;
    private ObjectMapper objectMapper;
    private UUID transactionId;
    private UUID extractionId;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        normalizer = new ConfidenceNormalizer();
        parser = new AIResponseParser(objectMapper, normalizer);
        transactionId = UUID.randomUUID();
        extractionId = UUID.randomUUID();
    }
    
    @Test
    @DisplayName("Should parse valid AI response")
    void testParseValidResponse() {
        String validResponse = """
            {
                "overallConfidence": 94.5,
                "summary": "Invoice appears internally consistent.",
                "findings": [
                    {
                        "type": "BENEFICIARY_MATCH",
                        "severity": "LOW",
                        "message": "Beneficiary matches.",
                        "confidence": 99.0,
                        "evidence": "Invoice: XYZ Ltd | Transaction: XYZ Ltd"
                    }
                ],
                "missingEvidence": ["Export declaration"],
                "recommendedChecks": ["Verify FEMA code"]
            }
            """;
        
        AIAnalysisResponse result = parser.parse(validResponse, transactionId, extractionId, "gpt-4", "v1");
        
        assertNotNull(result);
        assertEquals("94.50", result.getOverallConfidence().toPlainString());
        assertEquals("Invoice appears internally consistent.", result.getSummary());
        assertEquals(1, result.getFindings().size());
        assertEquals("BENEFICIARY_MATCH", result.getFindings().get(0).getType());
        assertEquals(1, result.getMissingEvidence().size());
        assertEquals(1, result.getRecommendedChecks().size());
    }
    
    @Test
    @DisplayName("Should reject null response")
    void testRejectNullResponse() {
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(null, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject empty response")
    void testRejectEmptyResponse() {
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse("", transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject non-JSON response")
    void testRejectNonJsonResponse() {
        String invalidJson = "This is not JSON at all";
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(invalidJson, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject array instead of object")
    void testRejectArrayResponse() {
        String arrayResponse = "[{\"key\": \"value\"}]";
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(arrayResponse, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject response missing overallConfidence")
    void testRejectMissingOverallConfidence() {
        String missingConfidence = """
            {
                "summary": "Test",
                "findings": []
            }
            """;
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(missingConfidence, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject response missing summary")
    void testRejectMissingSummary() {
        String missingSummary = """
            {
                "overallConfidence": 95.0,
                "findings": []
            }
            """;
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(missingSummary, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject response missing findings")
    void testRejectMissingFindings() {
        String missingFindings = """
            {
                "overallConfidence": 95.0,
                "summary": "Test"
            }
            """;
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(missingFindings, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject empty summary")
    void testRejectEmptySummary() {
        String emptySummary = """
            {
                "overallConfidence": 95.0,
                "summary": "",
                "findings": []
            }
            """;
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(emptySummary, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should reject finding missing required fields")
    void testRejectFindingMissingFields() {
        String missingFindingField = """
            {
                "overallConfidence": 95.0,
                "summary": "Test",
                "findings": [
                    {
                        "type": "MISMATCH",
                        "severity": "HIGH"
                    }
                ]
            }
            """;
        assertThrows(InvalidAIResponseException.class, () -> 
            parser.parse(missingFindingField, transactionId, extractionId, "gpt-4", "v1"));
    }
    
    @Test
    @DisplayName("Should normalize finding confidence scores")
    void testNormalizeConfidenceScores() {
        String responseWithHighConfidence = """
            {
                "overallConfidence": 150.0,
                "summary": "Test",
                "findings": [
                    {
                        "type": "TEST",
                        "severity": "LOW",
                        "message": "Test",
                        "confidence": 105.0,
                        "evidence": "Test"
                    }
                ]
            }
            """;
        
        AIAnalysisResponse result = parser.parse(responseWithHighConfidence, transactionId, extractionId, "gpt-4", "v1");
        assertEquals("100.00", result.getOverallConfidence().toPlainString());
        assertEquals(100.0, result.getFindings().get(0).getConfidence());
    }
    
    @Test
    @DisplayName("Should handle optional fields gracefully")
    void testHandleOptionalFields() {
        String minimalResponse = """
            {
                "overallConfidence": 85.0,
                "summary": "Analysis complete",
                "findings": []
            }
            """;
        
        AIAnalysisResponse result = parser.parse(minimalResponse, transactionId, extractionId, "gpt-4", "v1");
        assertNotNull(result);
        assertEquals(0, result.getFindings().size());
        assertTrue(result.getMissingEvidence().isEmpty());
        assertTrue(result.getRecommendedChecks().isEmpty());
    }
}
