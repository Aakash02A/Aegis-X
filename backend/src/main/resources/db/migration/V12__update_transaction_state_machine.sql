-- ============================================================================
-- V12: Update Transaction State Machine with Complete State Set
-- Adds missing states and is_terminal column according to TAS specification
-- ============================================================================

-- Add is_terminal column to transactions table
ALTER TABLE transactions 
ADD COLUMN is_terminal BOOLEAN NOT NULL DEFAULT FALSE;

-- Update the status column constraint to include all states from TAS specification
ALTER TABLE transactions 
DROP CONSTRAINT IF EXISTS chk_txn_status;

ALTER TABLE transactions 
ADD CONSTRAINT chk_txn_status CHECK (status IN (
    'CREATED',
    'PAYMENT_RECEIVED', 
    'PAYMENT_FAILED',
    'EVIDENCE_PENDING',
    'OCR_PROCESSING',
    'OCR_FAILED', 
    'AI_ANALYSIS',
    'AI_ANALYSIS_FAILED',
    'POLICY_EVALUATION',
    'APPROVED',
    'COMPLIANCE_HOLD',
    'REJECTED'
));

-- Update existing terminal state transactions
UPDATE transactions 
SET is_terminal = TRUE 
WHERE status IN ('APPROVED', 'REJECTED', 'PAYMENT_FAILED');

-- Add index on is_terminal for performance
CREATE INDEX idx_transactions_is_terminal ON transactions(is_terminal);

-- Add composite index for status and terminal state queries
CREATE INDEX idx_transactions_status_terminal ON transactions(status, is_terminal);

-- Add comment for documentation
COMMENT ON COLUMN transactions.is_terminal IS 'Indicates if transaction has reached a terminal state where no further transitions are allowed';