package com.aegisx.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ComplianceFinding represents a single AI-identified observation.
 * 
 * Purpose:
 * - Encapsulate individual compliance findings from AI analysis
 * - Each finding has a type, severity, confidence, and evidence trail
 * - Never represents approval or rejection; only advisory observations
 * 
 * Example:
 * {
 *   "type": "BENEFICIARY_MISMATCH",
 *   "severity": "HIGH",
 *   "message": "Invoice beneficiary differs from transaction beneficiary.",
 *   "confidence": 97.5,
 *   "evidence": "Invoice: XYZ Imports LLC | Transaction: XYZ Import Ltd"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceFinding {
    
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("severity")
    private String severity;
    
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("confidence")
    private Double confidence;
    
    @JsonProperty("evidence")
    private String evidence;
}
