/**
 * Policy module: Deterministic compliance policy engine.
 * 
 * Purpose:
 * - Evaluate transactions against deterministic compliance rules
 * - Produce final system decision: APPROVED or COMPLIANCE_HOLD
 * - Maintain immutable audit trail of all policy decisions
 * - Enable reproducible, explainable compliance determinations
 * 
 * Architecture:
 * - policy.entity: PolicyEvaluation JPA entity for persistence
 * - policy.repository: PolicyEvaluationRepository for data access
 * - policy.dto: PolicyEvaluationResponse, RuleResult, ComplianceDecision for API contracts
 * - policy.rules: 7 deterministic rules (DocumentCompletenessRule, BeneficiaryMatchRule, etc.)
 * - policy.service: RuleEngine orchestration, PolicyEvaluationService
 * - policy.controller: PolicyEvaluationController for REST endpoints
 * 
 * Frozen Invariants:
 * - Policy Engine is sole component that produces APPROVED/COMPLIANCE_HOLD
 * - AI findings treated as evidence, never as authority
 * - Every rule evaluation recorded immutably
 * - All decisions reproducible via same data + rules
 * - Rules execute deterministically in fixed order
 * - No external API called during policy evaluation
 * - Fail-closed: missing evidence triggers HOLD
 * 
 * Decision Flow:
 * 1. Load transaction, documents, OCR, AI data
 * 2. Build RuleContext
 * 3. Execute 7 rules
 * 4. Aggregate: any failure → COMPLIANCE_HOLD, all pass → APPROVED
 * 5. Persist decision with full audit trail
 * 6. Return decision to caller
 */
package com.aegisx.policy;
