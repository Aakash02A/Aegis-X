-- ============================================================================
-- V14: Create Policy Violations Table  
-- Purpose: Detailed tracking of policy rule failures during evaluation
-- According to TAS Section 6 - Complete Database Schema
-- ============================================================================

CREATE TABLE policy_violations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_evaluation_id UUID NOT NULL REFERENCES policy_evaluations(id) ON DELETE CASCADE,
    rule_id VARCHAR(20) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    rule_context JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_violation_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_violation_rule_id CHECK (rule_id ~ '^PR-[0-9]{2}$')
);

-- Indexes for query optimization
CREATE INDEX idx_policy_violations_eval_id ON policy_violations(policy_evaluation_id);
CREATE INDEX idx_policy_violations_rule_id ON policy_violations(rule_id);
CREATE INDEX idx_policy_violations_severity ON policy_violations(severity);
CREATE INDEX idx_policy_violations_created_at ON policy_violations(created_at);

-- Composite index for rule failure analysis
CREATE INDEX idx_policy_violations_rule_severity ON policy_violations(rule_id, severity);

-- Comments for documentation
COMMENT ON TABLE policy_violations IS 'Detailed tracking of policy rule failures. Each violation represents a failed compliance rule during policy evaluation.';

COMMENT ON COLUMN policy_violations.policy_evaluation_id IS 'Foreign key to policy_evaluations. Links violations to their evaluation context.';
COMMENT ON COLUMN policy_violations.rule_id IS 'Policy rule identifier (PR-01, PR-02, etc.) as defined in SRS Section 8.3';
COMMENT ON COLUMN policy_violations.rule_name IS 'Human-readable rule name for reporting and audit purposes';
COMMENT ON COLUMN policy_violations.severity IS 'Violation severity level: CRITICAL, HIGH, MEDIUM, LOW';
COMMENT ON COLUMN policy_violations.description IS 'Detailed explanation of why the rule failed';
COMMENT ON COLUMN policy_violations.rule_context IS 'Additional context data specific to the rule failure (JSON)';