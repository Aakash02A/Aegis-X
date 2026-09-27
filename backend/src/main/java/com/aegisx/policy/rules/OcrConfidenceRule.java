package com.aegisx.policy.rules;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * OcrConfidenceRule verifies that OCR extraction confidence meets minimum threshold.
 * 
 * Principle:
 * - OCR confidence must be >= 90%
 * - Low confidence indicates potential extraction errors
 * - Fail-closed: missing or low confidence triggers HOLD
 * 
 * Threshold:
 * - 90%: Recommended minimum for financial documents
 * - Can be adjusted via configuration for future iterations
 * 
 * Result:
 * - PASS: OCR confidence >= 90%
 * - FAIL: OCR confidence < 90% or missing
 */
@Component
public class OcrConfidenceRule implements PolicyRule {
    
    private static final String RULE_NAME = "OCR_CONFIDENCE";
    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("90");
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        // Get OCR confidence
        if (context.getDocumentExtraction() == null) {
            return fail("No OCR extraction record found");
        }
        
        Double confidence = context.getDocumentExtraction().getConfidenceScore();
        BigDecimal ocrConfidence = confidence == null ? null : BigDecimal.valueOf(confidence);
        
        if (ocrConfidence == null) {
            return fail("OCR confidence score is missing");
        }
        
        // Compare to threshold
        if (ocrConfidence.compareTo(CONFIDENCE_THRESHOLD) >= 0) {
            return pass(String.format("OCR confidence meets threshold (%.2f%% >= %.2f%%)", 
                ocrConfidence, CONFIDENCE_THRESHOLD), 
                String.format("OCR Confidence: %.2f%%", ocrConfidence));
        } else {
            return fail(String.format("OCR confidence too low (%.2f%% < %.2f%%)", 
                ocrConfidence, CONFIDENCE_THRESHOLD));
        }
    }
    
    private RuleEvaluationResult pass(String reason, String evidence) {
        return RuleEvaluationResult.builder()
            .ruleName(RULE_NAME)
            .passed(true)
            .reason(reason)
            .evidence(evidence)
            .build();
    }
    
    private RuleEvaluationResult fail(String reason) {
        return RuleEvaluationResult.builder()
            .ruleName(RULE_NAME)
            .passed(false)
            .reason(reason)
            .evidence("OCR confidence below acceptable threshold")
            .build();
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
