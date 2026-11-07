package com.aegisx.policy.rules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * CurrencyConsistencyRule verifies that transaction currency matches OCR extracted currency.
 * 
 * Principle:
 * - Currency codes must match exactly (ISO 4217)
 * - No automatic conversion
 * - For MVP: simple string comparison (USD, EUR, GBP, etc.)
 * 
 * Result:
 * - PASS: Transaction currency equals OCR currency
 * - FAIL: Currencies differ or OCR currency missing
 */
@Component
public class CurrencyConsistencyRule implements PolicyRule {
    
    private static final String RULE_NAME = "CURRENCY_CONSISTENCY";
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        // Get transaction currency
        if (context.getTransaction() == null || context.getTransaction().getCurrency() == null) {
            return fail("Transaction currency missing");
        }
        String txnCurrency = context.getTransaction().getCurrency().trim().toUpperCase(Locale.ROOT);
        
        // Get OCR currency
        String ocrCurrency = null;
        if (context.getDocumentExtraction() != null && context.getDocumentExtraction().getExtractedData() != null) {
            JsonNode ocrData = context.getDocumentExtraction().getExtractedData();
            JsonNode currencyNode = ocrData.get("currency");
            
            if (currencyNode != null && currencyNode.isTextual()) {
                ocrCurrency = currencyNode.asText().trim().toUpperCase(Locale.ROOT);
            }
        }
        
        if (ocrCurrency == null || ocrCurrency.isEmpty()) {
            return fail("OCR currency not extracted from document");
        }
        
        // Compare currencies (exact match only)
        if (txnCurrency.equals(ocrCurrency)) {
            return pass("Transaction currency matches OCR currency", 
                String.format("Currency: %s", txnCurrency));
        } else {
            return fail(String.format("Currency mismatch: Transaction declares %s but invoice shows %s", 
                txnCurrency, ocrCurrency));
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
            .evidence("Currency verification failed")
            .build();
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
