-- Align persisted transaction states with the implemented settlement lifecycle.
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_txn_status;
ALTER TABLE transactions ADD CONSTRAINT chk_txn_status CHECK (status IN (
    'CREATED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'EVIDENCE_PENDING',
    'OCR_PROCESSING', 'OCR_FAILED', 'AI_ANALYSIS', 'AI_ANALYSIS_FAILED',
    'POLICY_EVALUATION', 'APPROVED', 'COMPLIANCE_HOLD', 'REJECTED',
    'SETTLEMENT_PENDING', 'SETTLED', 'SETTLEMENT_FAILED'
));

UPDATE transactions SET is_terminal = TRUE WHERE status IN ('SETTLED', 'REJECTED', 'PAYMENT_FAILED');
UPDATE transactions SET is_terminal = FALSE WHERE status NOT IN ('SETTLED', 'REJECTED', 'PAYMENT_FAILED');
