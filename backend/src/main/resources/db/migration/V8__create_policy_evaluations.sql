-- V8__create_policy_evaluations.sql
-- Create immutable policy decision storage for compliance determinations.
-- Stores final system decisions: APPROVED or COMPLIANCE_HOLD.
-- Every decision is persisted with full rule audit trail.

CREATE TABLE IF NOT EXISTS policy_evaluations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    ai_analysis_id UUID,
    final_decision VARCHAR(50) NOT NULL CHECK (final_decision IN ('APPROVED', 'COMPLIANCE_HOLD')),
    overall_score DECIMAL(5, 2),
    evaluation_json JSONB NOT NULL,
    processing_duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_policy_eval_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE,
    CONSTRAINT fk_policy_eval_ai_analysis FOREIGN KEY (ai_analysis_id) REFERENCES ai_analysis (id) ON DELETE SET NULL
);

-- Indexes for query optimization
CREATE INDEX idx_policy_eval_transaction_id ON policy_evaluations(transaction_id);
CREATE INDEX idx_policy_eval_created_at ON policy_evaluations(created_at DESC);
CREATE INDEX idx_policy_eval_final_decision ON policy_evaluations(final_decision);

-- Immutability constraint: no updates, only inserts
COMMENT ON TABLE policy_evaluations IS 'Immutable audit log of policy evaluations. Each decision is final and permanently recorded; no updates or deletes except cascade from parent transaction.';
