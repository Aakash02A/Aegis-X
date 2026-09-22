package com.aegisx.policy.rules;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * AiConfidenceRule verifies that AI analysis confidence meets minimum threshold.
 * 
 * Principle:
 * - AI confidence must be >= 85%
 * - Low confidence indicates AI uncertainty about findings
 * - Fail-closed: missing or low confidence triggers HOLD
 * - AI confidence is ONE RULE among many, not the deciding factor
 * 
 * Threshold:
 * - 85%: Allows for AI uncertainty while maintaining compliance gate
 * - Can be adjusted via configuration for future iterations
 * 
 * Important:
 * - This is advisory input, not the final decision
 * - Other rules are equally important
 * - Human compliance officers will review HOLD decisions
 * 
 * Result:
 * - PASS: AI confidence >= 85%
 * - FAIL: AI confidence < 85% or missing or no AI analysis
 */
@Component
public class AiConfidenceRule implements PolicyRule {
    
    private static final String RULE_NAME = "AI_CONFIDENCE";
    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("85");
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        // Get AI analysis
        if (context.getAiAnalysis() == null) {
            return fail("No AI analysis record found");
        }
        
        BigDecimal aiConfidence = context.getAiAnalysis().getConfidenceScore();
        
        if (aiConfidence == null) {
            return fail("AI confidence score is missing");
        }
        
        // Compare to threshold
        if (aiConfidence.compareTo(CONFIDENCE_THRESHOLD) >= 0) {
            return pass(String.format("AI confidence meets threshold (%.2f%% >= %.2f%%)", 
                aiConfidence, CONFIDENCE_THRESHOLD), 
                String.format("AI Confidence: %.2f%%", aiConfidence));
        } else {
            return fail(String.format("AI confidence below threshold (%.2f%% < %.2f%%)", 
                aiConfidence, CONFIDENCE_THRESHOLD));
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
            .evidence("AI confidence below acceptable threshold")
            .build();
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
