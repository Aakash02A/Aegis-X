package com.aegisx.policy.rules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * BeneficiaryMatchRule compares beneficiary names across sources.
 * 
 * Sources are the transaction beneficiary and the {@code beneficiary} or {@code vendor_name}
 * structured OCR field. AI output and confidence scores are not consulted.
 * 
 * Result:
 * - PASS: Beneficiaries are consistent
 * - FAIL: Mismatch detected between sources
 */
@Component
public class BeneficiaryMatchRule implements PolicyRule {
    
    private static final String RULE_NAME = "BENEFICIARY_MATCH";
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        // Get transaction beneficiary
        if (context.getTransaction() == null || context.getTransaction().getBeneficiaryName() == null) {
            return fail("Transaction beneficiary missing");
        }
        String txnBeneficiary = context.getTransaction().getBeneficiaryName().trim().toLowerCase(java.util.Locale.ROOT);
        
        // Get OCR beneficiary
        String ocrBeneficiary = null;
        if (context.getDocumentExtraction() != null && context.getDocumentExtraction().getExtractedData() != null) {
            JsonNode ocrData = context.getDocumentExtraction().getExtractedData();
            if (ocrData.has("beneficiary") || ocrData.has("vendor_name")) {
                JsonNode beneficiaryNode = ocrData.has("beneficiary") 
                    ? ocrData.get("beneficiary") 
                    : ocrData.get("vendor_name");
                if (beneficiaryNode != null && beneficiaryNode.isTextual()) {
                    ocrBeneficiary = beneficiaryNode.asText().trim().toLowerCase(java.util.Locale.ROOT);
                }
            }
        }
        
        if (ocrBeneficiary == null || ocrBeneficiary.isEmpty()) {
            return fail("OCR beneficiary not extracted from document");
        }
        
        // Compare beneficiaries (allow for minor variations)
        if (isMatch(txnBeneficiary, ocrBeneficiary)) {
            return pass("Transaction and OCR beneficiaries match", 
                String.format("Transaction: %s | OCR: %s", txnBeneficiary, ocrBeneficiary));
        } else {
            return fail(String.format("Beneficiary mismatch: Transaction declares '%s' but invoice shows '%s'", 
                context.getTransaction().getBeneficiaryName(), ocrBeneficiary));
        }
    }
    
    /**
     * Simple beneficiary matching: exact or close match.
     * Can be enhanced to handle abbreviations, acronyms, etc.
     */
    private boolean isMatch(String txn, String ocr) {
        // Exact match
        if (txn.equals(ocr)) {
            return true;
        }
        // Contains match (e.g., "XYZ Imports LLC" vs "XYZ Imports")
        if (txn.contains(ocr) || ocr.contains(txn)) {
            return true;
        }
        return false;
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
            .evidence("Beneficiary verification failed")
            .build();
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
