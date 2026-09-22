package com.aegisx.policy.rules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * AmountConsistencyRule verifies that transaction amount matches OCR extracted amount.
 * 
 * Principle:
 * - Amount must match exactly (no automatic conversion or rounding)
 * - No tolerance for small differences
 * - Missing OCR amount causes fail-closed (HOLD)
 * 
 * Result:
 * - PASS: Transaction amount equals OCR amount
 * - FAIL: Amounts differ or OCR amount missing
 */
@Component
public class AmountConsistencyRule implements PolicyRule {
    
    private static final String RULE_NAME = "AMOUNT_CONSISTENCY";
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        // Get transaction amount
        if (context.getTransaction() == null || context.getTransaction().getAmount() == null) {
            return fail("Transaction amount missing");
        }
        BigDecimal txnAmount = context.getTransaction().getAmount();
        
        // Get OCR amount
        BigDecimal ocrAmount = null;
        if (context.getDocumentExtraction() != null && context.getDocumentExtraction().getExtractedData() != null) {
            JsonNode ocrData = context.getDocumentExtraction().getExtractedData();
            JsonNode amountNode = ocrData.get("total_amount") != null 
                ? ocrData.get("total_amount") 
                : ocrData.get("amount");
            
            if (amountNode != null && amountNode.isTextual()) {
                try {
                    ocrAmount = new BigDecimal(amountNode.asText());
                } catch (NumberFormatException e) {
                    return fail("OCR amount is not a valid number: " + amountNode.asText());
                }
            } else if (amountNode != null && amountNode.isNumber()) {
                ocrAmount = amountNode.decimalValue();
            }
        }
        
        if (ocrAmount == null) {
            return fail("OCR amount not extracted from document");
        }
        
        // Compare amounts (exact match only)
        if (txnAmount.compareTo(ocrAmount) == 0) {
            return pass("Transaction amount matches OCR amount", 
                String.format("Amount: %s %s", txnAmount, context.getTransaction().getCurrency()));
        } else {
            return fail(String.format("Amount mismatch: Transaction declares %s but invoice shows %s", 
                txnAmount, ocrAmount));
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
            .evidence("Amount verification failed")
            .build();
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
