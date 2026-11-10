package com.aegisx.policy.service;

import com.aegisx.policy.dto.ComplianceDecision;
import com.aegisx.policy.rules.AmountConsistencyRule;
import com.aegisx.policy.rules.BeneficiaryMatchRule;
import com.aegisx.policy.rules.CurrencyConsistencyRule;
import com.aegisx.policy.rules.DocumentCompletenessRule;
import com.aegisx.policy.rules.PurposeCodeRule;
import com.aegisx.policy.rules.RuleContext;
import com.aegisx.policy.rules.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RuleEngine orchestrates deterministic policy rule evaluation.
 * 
 * Workflow:
 * 1. Build RuleContext from transaction, required documents and OCR structured fields
 * 2. Execute the five deterministic rules sequentially
 * 3. Aggregate results: any failure → COMPLIANCE_HOLD, all pass → APPROVED
 * 4. Record rule results with evidence for auditability
 * 5. Return final decision and rule details
 * 
 * Design:
 * - Pure function: same input always produces same output
 * - No side effects: no database calls during rule execution
 * - Deterministic: rules run in fixed order
 * - Fail-closed: missing transaction, checklist or extracted values fail their corresponding rule
 * - No rule reads AI output or OCR confidence scores
 * - Full audit trail: every rule decision is recorded
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngine {
    
    private final DocumentCompletenessRule documentCompletenessRule;
    private final BeneficiaryMatchRule beneficiaryMatchRule;
    private final AmountConsistencyRule amountConsistencyRule;
    private final CurrencyConsistencyRule currencyConsistencyRule;
    private final PurposeCodeRule purposeCodeRule;
    
    /**
     * Execute all rules and produce final compliance decision.
     * 
     * @param context RuleContext containing all data needed for evaluation
     * @return PolicyEngineResult with final decision and rule results
     */
    public PolicyEngineResult evaluate(RuleContext context) {
        long startTime = System.currentTimeMillis();
        
        log.debug("Starting policy evaluation for transaction: {}", context.getTransaction().getId());
        
        // Execute all rules in order
        List<RuleEvaluationResult> ruleResults = new ArrayList<>();
        ruleResults.add(documentCompletenessRule.evaluate(context));
        ruleResults.add(beneficiaryMatchRule.evaluate(context));
        ruleResults.add(amountConsistencyRule.evaluate(context));
        ruleResults.add(currencyConsistencyRule.evaluate(context));
        ruleResults.add(purposeCodeRule.evaluate(context));
        
        // Aggregate results: any failure → HOLD, all pass → APPROVED
        boolean allPass = ruleResults.stream().allMatch(RuleEvaluationResult::getPassed);
        String finalDecision = allPass ? ComplianceDecision.APPROVED.name() : ComplianceDecision.COMPLIANCE_HOLD.name();
        
        long processingDuration = System.currentTimeMillis() - startTime;
        
        log.info("Policy evaluation completed for transaction: {} - Decision: {}, Duration: {}ms, Rules: {}/{} passed",
            context.getTransaction().getId(), finalDecision, processingDuration,
            ruleResults.stream().filter(RuleEvaluationResult::getPassed).count(), ruleResults.size());
        
        return PolicyEngineResult.builder()
            .finalDecision(finalDecision)
            .ruleResults(ruleResults)
            .processingDurationMs(processingDuration)
            .build();
    }
}
