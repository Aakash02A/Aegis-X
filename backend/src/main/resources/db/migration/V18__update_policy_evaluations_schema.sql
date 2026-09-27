-- ============================================================================
-- V16: Update Policy Evaluations Schema to Match TAS Specification
-- Purpose: Align policy_evaluations table with complete TAS schema
-- ============================================================================

-- Add new columns to match TAS specification
ALTER TABLE policy_evaluations 
ADD COLUMN IF NOT EXISTS verdict VARCHAR(20),
ADD COLUMN IF NOT EXISTS total_rules_count INTEGER,
ADD COLUMN IF NOT EXISTS passed_rules_count INTEGER,
ADD COLUMN IF NOT EXISTS failed_rules_count INTEGER,
ADD COLUMN IF NOT EXISTS engine_version VARCHAR(20) DEFAULT '1.0.0',
ADD COLUMN IF NOT EXISTS evaluated_at TIMESTAMPTZ;

-- Update existing records to populate new fields from legacy data
UPDATE policy_evaluations 
SET 
    verdict = CASE 
        WHEN final_decision = 'APPROVED' THEN 'APPROVED'
        WHEN final_decision = 'COMPLIANCE_HOLD' THEN 'HOLD'
        ELSE 'HOLD'
    END,
    evaluated_at = COALESCE(created_at, CURRENT_TIMESTAMP),
    engine_version = COALESCE(engine_version, '1.0.0')
WHERE verdict IS NULL;

-- Add constraints for new columns
ALTER TABLE policy_evaluations 
ADD CONSTRAINT chk_policy_verdict CHECK (verdict IN ('APPROVED', 'HOLD'));

-- Add indexes for new columns
CREATE INDEX IF NOT EXISTS idx_policy_eval_verdict ON policy_evaluations(verdict);
CREATE INDEX IF NOT EXISTS idx_policy_eval_evaluated_at ON policy_evaluations(evaluated_at);
CREATE INDEX IF NOT EXISTS idx_policy_eval_engine_version ON policy_evaluations(engine_version);

-- Add composite index for performance queries
CREATE INDEX IF NOT EXISTS idx_policy_eval_txn_verdict ON policy_evaluations(transaction_id, verdict);

-- Update comments
COMMENT ON COLUMN policy_evaluations.verdict IS 'Policy engine verdict: APPROVED or HOLD';
COMMENT ON COLUMN policy_evaluations.total_rules_count IS 'Total number of rules evaluated';
COMMENT ON COLUMN policy_evaluations.passed_rules_count IS 'Number of rules that passed';
COMMENT ON COLUMN policy_evaluations.failed_rules_count IS 'Number of rules that failed';
COMMENT ON COLUMN policy_evaluations.engine_version IS 'Version of the policy engine that performed the evaluation';
COMMENT ON COLUMN policy_evaluations.evaluated_at IS 'Timestamp when the evaluation was performed';

-- Add foreign key constraints if they don't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'fk_policy_eval_transaction_new' 
        AND table_name = 'policy_evaluations'
    ) THEN
        ALTER TABLE policy_evaluations 
        ADD CONSTRAINT fk_policy_eval_transaction_new 
        FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE;
    END IF;
END $$;