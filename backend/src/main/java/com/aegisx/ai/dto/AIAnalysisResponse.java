package com.aegisx.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AIAnalysisResponse represents the API response structure for AI analysis results.
 * 
 * Purpose:
 * - Return persisted AI findings to client
 * - Include full audit trail (model, version, hash, confidence, duration)
 * - Enable UI rendering of advisory findings and evidence
 * - Never include approval or rejection indicators
 * 
 * Example:
 * {
 *   "id": "uuid",
 *   "transactionId": "uuid",
 *   "extractionId": "uuid",
 *   "modelName": "gpt-4",
 *   "promptVersion": "v1",
 *   "inputHash": "sha256...",
 *   "overallConfidence": 94.5,
 *   "summary": "Invoice appears internally consistent.",
 *   "findings": [
 *     {
 *       "type": "BENEFICIARY_MATCH",
 *       "severity": "LOW",
 *       "confidence": 99,
 *       "evidence": "Invoice beneficiary matches transaction."
 *     }
 *   ],
 *   "missingEvidence": ["Export declaration not available."],
 *   "recommendedChecks": ["Verify FEMA purpose code against merchant category."],
 *   "processingDurationMs": 2350,
 *   "createdAt": "2026-09-22T14:30:45"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIAnalysisResponse {
    
    @JsonProperty("id")
    private UUID id;
    
    @JsonProperty("transactionId")
    private UUID transactionId;
    
    @JsonProperty("extractionId")
    private UUID extractionId;
    
    @JsonProperty("modelName")
    private String modelName;
    
    @JsonProperty("promptVersion")
    private String promptVersion;
    
    @JsonProperty("inputHash")
    private String inputHash;
    
    @JsonProperty("overallConfidence")
    private BigDecimal overallConfidence;
    
    @JsonProperty("summary")
    private String summary;
    
    @JsonProperty("findings")
    private List<ComplianceFinding> findings;
    
    @JsonProperty("missingEvidence")
    private List<String> missingEvidence;
    
    @JsonProperty("recommendedChecks")
    private List<String> recommendedChecks;
    
    @JsonProperty("processingDurationMs")
    private Long processingDurationMs;
    
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
