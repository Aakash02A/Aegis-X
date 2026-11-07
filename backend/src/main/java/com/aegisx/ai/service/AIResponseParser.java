package com.aegisx.ai.service;

import com.aegisx.ai.dto.AIAnalysisResponse;
import com.aegisx.ai.dto.ComplianceFinding;
import com.aegisx.common.exception.InvalidAIResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AIResponseParser validates and extracts AI response into structured DTOs.
 * 
 * Purpose:
 * - Validate that AI returned strict JSON (not prose or markdown)
 * - Extract overallConfidence, summary, findings, missingEvidence, recommendedChecks
 * - Normalize confidence scores via ConfidenceNormalizer
 * - Reject malformed responses with clear error messages
 * - Convert raw JsonNode to AIAnalysisResponse DTO
 * 
 * Design:
 * - Strict JSON validation: must be parseable, required fields present
 * - Field extraction: findings array with type/severity/confidence/evidence
 * - Confidence normalization: ensure all scores in [0, 100] with 2 decimals
 * - No partial success: if any field is invalid, reject entire response
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AIResponseParser {
    
    private final ObjectMapper objectMapper;
    private final ConfidenceNormalizer confidenceNormalizer;
    
    /**
     * Parse AI response text into AIAnalysisResponse DTO.
     * 
     * @param aiResponse Raw text response from Spring AI
     * @param transactionId Transaction being analyzed
     * @param extractionId DocumentExtraction being analyzed
     * @param modelName AI model name (e.g., "gpt-4")
     * @param promptVersion Prompt template version
     * @return Validated and normalized AIAnalysisResponse
     * @throws InvalidAIResponseException if response is malformed or invalid
     */
    public AIAnalysisResponse parse(String aiResponse, UUID transactionId, UUID extractionId, 
                                    String modelName, String promptVersion) {
        if (aiResponse == null || aiResponse.isBlank()) {
            throw new InvalidAIResponseException("AI response is empty or null");
        }
        
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(aiResponse);
        } catch (Exception e) {
            throw new InvalidAIResponseException("AI response is not valid JSON", e);
        }
        
        if (!jsonNode.isObject()) {
            throw new InvalidAIResponseException("AI response must be a JSON object, got: " + jsonNode.getNodeType());
        }
        
        // Validate required fields
        if (!jsonNode.has("overallConfidence")) {
            throw new InvalidAIResponseException("Missing required field: overallConfidence");
        }
        if (!jsonNode.has("summary")) {
            throw new InvalidAIResponseException("Missing required field: summary");
        }
        if (!jsonNode.has("findings")) {
            throw new InvalidAIResponseException("Missing required field: findings");
        }
        
        // Extract overallConfidence
        BigDecimal overallConfidence;
        try {
            Double confidenceValue = requiredNumber(jsonNode, "overallConfidence");
            overallConfidence = confidenceNormalizer.normalize(confidenceValue);
        } catch (Exception e) {
            throw new InvalidAIResponseException("Invalid overallConfidence value", e);
        }
        
        // Extract summary
        String summary = requiredText(jsonNode, "summary");
        
        // Extract findings
        List<ComplianceFinding> findings;
        try {
            findings = parseFindingsArray(jsonNode.get("findings"));
        } catch (Exception e) {
            throw new InvalidAIResponseException("Invalid findings array", e);
        }
        
        // Extract missingEvidence (optional)
        List<String> missingEvidence = parseStringArray(jsonNode.get("missingEvidence"));
        
        // Extract recommendedChecks (optional)
        List<String> recommendedChecks = parseStringArray(jsonNode.get("recommendedChecks"));
        
        // Build and return response
        return AIAnalysisResponse.builder()
            .id(UUID.randomUUID())
            .transactionId(transactionId)
            .extractionId(extractionId)
            .modelName(modelName)
            .promptVersion(promptVersion)
            .overallConfidence(overallConfidence)
            .summary(summary)
            .findings(findings)
            .missingEvidence(missingEvidence)
            .recommendedChecks(recommendedChecks)
            .createdAt(LocalDateTime.now())
            .build();
    }
    
    /**
     * Parse findings array from JSON node.
     */
    private List<ComplianceFinding> parseFindingsArray(JsonNode findingsNode) {
        List<ComplianceFinding> findings = new ArrayList<>();
        
        if (findingsNode == null || !findingsNode.isArray()) {
            throw new InvalidAIResponseException("findings must be an array");
        }
        
        for (JsonNode findingNode : findingsNode) {
            if (!findingNode.isObject()) {
                throw new InvalidAIResponseException("Each finding must be a JSON object");
            }
            
            // Validate required finding fields
            if (!findingNode.has("type")) {
                throw new InvalidAIResponseException("Finding missing required field: type");
            }
            if (!findingNode.has("severity")) {
                throw new InvalidAIResponseException("Finding missing required field: severity");
            }
            if (!findingNode.has("confidence")) {
                throw new InvalidAIResponseException("Finding missing required field: confidence");
            }
            if (!findingNode.has("evidence")) {
                throw new InvalidAIResponseException("Finding missing required field: evidence");
            }
            
            String type = requiredText(findingNode, "type");
            String severity = requiredText(findingNode, "severity");
            String message = optionalText(findingNode, "message");
            Double confidence = requiredNumber(findingNode, "confidence");
            String evidence = requiredText(findingNode, "evidence");
            
            BigDecimal normalizedConfidence = confidenceNormalizer.normalize(confidence);
            
            findings.add(ComplianceFinding.builder()
                .type(type)
                .severity(severity)
                .message(message)
                .confidence(normalizedConfidence.doubleValue())
                .evidence(evidence)
                .build());
        }
        
        return findings;
    }
    
    /**
     * Parse string array from JSON node (optional).
     */
    private List<String> parseStringArray(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        
        if (arrayNode == null || arrayNode.isNull()) {
            return result;
        }
        
        if (!arrayNode.isArray()) {
            throw new InvalidAIResponseException("Field must be an array");
        }
        
        for (JsonNode element : arrayNode) {
            if (element.isTextual()) {
                result.add(element.asText());
            } else {
                throw new InvalidAIResponseException("Field array elements must be strings");
            }
        }
        
        return result;
    }
    
    /** Serializes only the fixed, validated response schema; unknown model fields are discarded. */
    public JsonNode toCanonicalJsonNode(AIAnalysisResponse response) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("overallConfidence", response.getOverallConfidence());
        canonical.put("summary", response.getSummary());

        ArrayNode findings = canonical.putArray("findings");
        for (ComplianceFinding finding : response.getFindings()) {
            ObjectNode findingNode = findings.addObject();
            findingNode.put("type", finding.getType());
            findingNode.put("severity", finding.getSeverity());
            findingNode.put("message", finding.getMessage());
            findingNode.put("confidence", finding.getConfidence());
            findingNode.put("evidence", finding.getEvidence());
        }

        ArrayNode missingEvidence = canonical.putArray("missingEvidence");
        response.getMissingEvidence().forEach(missingEvidence::add);
        ArrayNode recommendedChecks = canonical.putArray("recommendedChecks");
        response.getRecommendedChecks().forEach(recommendedChecks::add);
        return canonical;
    }

    private String requiredText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidAIResponseException("Field must be a non-empty string: " + fieldName);
        }
        return value.asText();
    }

    private String optionalText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new InvalidAIResponseException("Field must be a string: " + fieldName);
        }
        return value.asText();
    }

    private Double requiredNumber(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isNumber()) {
            throw new InvalidAIResponseException("Field must be a number: " + fieldName);
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number)) {
            throw new InvalidAIResponseException("Field is not a finite number: " + fieldName);
        }
        return number;
    }
}
