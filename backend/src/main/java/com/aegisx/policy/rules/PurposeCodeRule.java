package com.aegisx.policy.rules;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PurposeCodeRule verifies that transaction purpose code is valid.
 * 
 * For MVP:
 * - Uses deterministic whitelist of valid FEMA codes
 * - Purpose code must be non-empty and in whitelist
 * 
 * Future:
 * - Database-driven FEMA mapping
 * - Validation against import/export regulations
 * - Merchant-specific allowed purposes
 * 
 * Result:
 * - PASS: Purpose code is in whitelist
 * - FAIL: Purpose code missing or not in whitelist
 */
@Component
public class PurposeCodeRule implements PolicyRule {
    
    private static final String RULE_NAME = "PURPOSE_CODE";
    
    // MVP whitelist of allowed FEMA purpose codes for exports
    private static final Set<String> VALID_CODES = Set.of(
        "P0101",  // Goods for sale
        "P0102",  // Services
        "P0105",  // Goods for exchange
        "P0103",  // Goods for resale
        "P0104",  // Goods for distribution
        "P0201",  // Business equipment
        "P0202",  // Supplies
        "P0301",  // Personal goods
        "P0401"   // Gift
    );
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        // Get purpose code
        if (context.getTransaction() == null || context.getTransaction().getPurpose() == null) {
            return fail("Transaction purpose code missing");
        }
        
        String purposeCode = context.getTransaction().getPurpose().trim().toUpperCase();
        
        if (purposeCode.isEmpty()) {
            return fail("Transaction purpose code is empty");
        }
        
        // Check whitelist
        if (VALID_CODES.contains(purposeCode)) {
            return pass("Purpose code is valid", String.format("Code: %s", purposeCode));
        } else {
            return fail(String.format("Purpose code '%s' is not in approved list", purposeCode));
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
            .evidence("Purpose code validation failed")
            .build();
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
