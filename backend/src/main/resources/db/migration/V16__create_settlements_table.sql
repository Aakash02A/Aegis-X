-- Create settlements table
-- Implements settlement tracking according to TAS Section 9.2

CREATE TABLE settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    settlement_reference VARCHAR(50) NOT NULL UNIQUE,
    payment_processor VARCHAR(50) NOT NULL,
    processor_transaction_id VARCHAR(100),
    status VARCHAR(50) NOT NULL,
    settlement_amount DECIMAL(15,2) NOT NULL,
    settlement_currency VARCHAR(3) NOT NULL,
    exchange_rate DECIMAL(10,6),
    processing_fees DECIMAL(10,2),
    initiated_at TIMESTAMP NOT NULL,
    submitted_at TIMESTAMP,
    completed_at TIMESTAMP,
    expected_completion_at TIMESTAMP,
    failure_reason VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP,
    processor_metadata JSONB,
    reconciliation_data JSONB,
    reconciled_at TIMESTAMP,
    initiated_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key constraints
    CONSTRAINT fk_settlements_transaction FOREIGN KEY (transaction_id) 
        REFERENCES transactions(id) ON DELETE RESTRICT,
    
    -- Check constraints
    CONSTRAINT chk_settlement_amount_positive CHECK (settlement_amount > 0),
    CONSTRAINT chk_settlement_currency_format CHECK (settlement_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_exchange_rate_positive CHECK (exchange_rate IS NULL OR exchange_rate > 0),
    CONSTRAINT chk_processing_fees_non_negative CHECK (processing_fees IS NULL OR processing_fees >= 0),
    CONSTRAINT chk_retry_count_non_negative CHECK (retry_count >= 0),
    CONSTRAINT chk_max_retries_positive CHECK (max_retries > 0),
    CONSTRAINT chk_retry_count_within_max CHECK (retry_count <= max_retries),
    CONSTRAINT chk_status_valid CHECK (status IN (
        'INITIATED', 'PROCESSING', 'SUBMITTED', 'PENDING', 'COMPLETED',
        'FAILED', 'REJECTED', 'CANCELLED', 'MANUAL_REVIEW', 
        'RECONCILING', 'RECONCILED', 'RECONCILIATION_FAILED'
    )),
    CONSTRAINT chk_payment_processor_valid CHECK (payment_processor IN (
        'SWIFT', 'RIPPLE', 'VISA_DIRECT', 'MASTERCARD_SEND', 
        'ACH', 'SEPA', 'FASTER_PAYMENTS', 'MOCK'
    ))
);

-- Create indexes for performance
CREATE INDEX idx_settlements_transaction_id ON settlements(transaction_id);
CREATE INDEX idx_settlements_status ON settlements(status);
CREATE INDEX idx_settlements_processor ON settlements(payment_processor);
CREATE INDEX idx_settlements_initiated_at ON settlements(initiated_at);
CREATE INDEX idx_settlements_reference ON settlements(settlement_reference);
CREATE INDEX idx_settlements_processor_txn_id ON settlements(processor_transaction_id);
CREATE INDEX idx_settlements_status_retry ON settlements(status, next_retry_at) WHERE status IN ('FAILED', 'REJECTED');
CREATE INDEX idx_settlements_overdue ON settlements(expected_completion_at, status) 
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'RECONCILED');
CREATE INDEX idx_settlements_reconciliation ON settlements(status, reconciled_at) WHERE status = 'COMPLETED';

-- Create partial indexes for specific queries
CREATE INDEX idx_settlements_pending_retry ON settlements(next_retry_at) 
    WHERE status IN ('FAILED', 'REJECTED') AND retry_count < max_retries;
CREATE INDEX idx_settlements_manual_intervention ON settlements(initiated_at) 
    WHERE status IN ('MANUAL_REVIEW', 'RECONCILIATION_FAILED');

-- Add trigger for updated_at timestamp
CREATE OR REPLACE FUNCTION update_settlements_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER settlements_updated_at
    BEFORE UPDATE ON settlements
    FOR EACH ROW
    EXECUTE FUNCTION update_settlements_updated_at();

-- Add comments for documentation
COMMENT ON TABLE settlements IS 'Settlement records for transaction processing';
COMMENT ON COLUMN settlements.id IS 'Primary key - settlement UUID';
COMMENT ON COLUMN settlements.transaction_id IS 'Reference to transactions table';
COMMENT ON COLUMN settlements.settlement_reference IS 'Unique settlement reference number';
COMMENT ON COLUMN settlements.payment_processor IS 'Payment processor handling settlement';
COMMENT ON COLUMN settlements.processor_transaction_id IS 'External processor transaction ID';
COMMENT ON COLUMN settlements.status IS 'Current settlement status';
COMMENT ON COLUMN settlements.settlement_amount IS 'Amount to be settled';
COMMENT ON COLUMN settlements.settlement_currency IS 'Settlement currency code (ISO 4217)';
COMMENT ON COLUMN settlements.exchange_rate IS 'Exchange rate applied if currency conversion';
COMMENT ON COLUMN settlements.processing_fees IS 'Fees charged by payment processor';
COMMENT ON COLUMN settlements.initiated_at IS 'When settlement was initiated';
COMMENT ON COLUMN settlements.submitted_at IS 'When settlement was submitted to processor';
COMMENT ON COLUMN settlements.completed_at IS 'When settlement was completed';
COMMENT ON COLUMN settlements.expected_completion_at IS 'Expected completion timestamp';
COMMENT ON COLUMN settlements.failure_reason IS 'Reason for failure (if failed)';
COMMENT ON COLUMN settlements.retry_count IS 'Number of retry attempts';
COMMENT ON COLUMN settlements.max_retries IS 'Maximum allowed retry attempts';
COMMENT ON COLUMN settlements.next_retry_at IS 'Next retry attempt timestamp';
COMMENT ON COLUMN settlements.processor_metadata IS 'Processor-specific data and responses';
COMMENT ON COLUMN settlements.reconciliation_data IS 'Bank reconciliation information';
COMMENT ON COLUMN settlements.reconciled_at IS 'When settlement was reconciled';
COMMENT ON COLUMN settlements.initiated_by IS 'User who initiated the settlement';

-- Insert audit event types for settlements
INSERT INTO audit_event_types (event_type, description, module) VALUES
('SETTLEMENT_INITIATED', 'Settlement request initiated', 'SETTLEMENT'),
('SETTLEMENT_SUBMITTED', 'Settlement submitted to processor', 'SETTLEMENT'),
('SETTLEMENT_COMPLETED', 'Settlement completed successfully', 'SETTLEMENT'),
('SETTLEMENT_FAILED', 'Settlement processing failed', 'SETTLEMENT'),
('SETTLEMENT_CANCELLED', 'Settlement cancelled', 'SETTLEMENT'),
('SETTLEMENT_RETRY', 'Settlement retry attempted', 'SETTLEMENT'),
('SETTLEMENT_RECONCILED', 'Settlement reconciliation completed', 'SETTLEMENT'),
('SETTLEMENT_STATUS_UPDATED', 'Settlement status updated', 'SETTLEMENT'),
('SETTLEMENT_VIEW', 'Settlement details viewed', 'SETTLEMENT')
ON CONFLICT (event_type) DO NOTHING;