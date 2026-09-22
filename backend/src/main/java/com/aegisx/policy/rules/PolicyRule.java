package com.aegisx.policy.rules;

/**
 * PolicyRule is the contract for all deterministic compliance rules.
 * 
 * Design:
 * - Each rule is independent and self-contained
 * - Rules are pure functions: same input always produces same output
 * - No side effects; no database calls inside rule evaluation
 * - All data needed is passed via RuleContext
 * - Rules execute in deterministic order within RuleEngine
 * 
 * Implementation:
 * - Each rule receives identical RuleContext
 * - Returns RuleEvaluationResult with passed/failed status, reason, evidence
 * - Reason and evidence are human-readable for audit trail
 */
public interface PolicyRule {
    
    /**
     * Evaluate this rule against the provided context.
     * 
     * @param context RuleContext containing all data needed for evaluation
     * @return RuleEvaluationResult with pass/fail status and evidence
     */
    RuleEvaluationResult evaluate(RuleContext context);
    
    /**
     * Get the human-readable name of this rule.
     * Used in audit trail and reporting.
     */
    String getRuleName();
}
