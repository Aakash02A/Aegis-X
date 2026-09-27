-- ============================================================================
-- V13: Create Evidence Checklists Table
-- Purpose: Track document requirements and satisfaction status per transaction
-- According to TAS Section 6 - Complete Database Schema
-- ============================================================================

CREATE TABLE evidence_checklists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL UNIQUE REFERENCES transactions(id) ON DELETE CASCADE,
    invoice_required BOOLEAN NOT NULL DEFAULT TRUE,
    invoice_uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    shipping_bill_required BOOLEAN NOT NULL DEFAULT FALSE,
    shipping_bill_uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    packing_list_required BOOLEAN NOT NULL DEFAULT FALSE,
    packing_list_uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    certificate_origin_required BOOLEAN NOT NULL DEFAULT FALSE,
    certificate_origin_uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    is_satisfied BOOLEAN NOT NULL DEFAULT FALSE,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for query optimization
CREATE INDEX idx_evidence_checklist_txn_id ON evidence_checklists(transaction_id);
CREATE INDEX idx_evidence_checklist_satisfied ON evidence_checklists(is_satisfied);
CREATE INDEX idx_evidence_checklist_evaluated_at ON evidence_checklists(evaluated_at);

-- Trigger to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_evidence_checklist_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_evidence_checklist_updated_at
    BEFORE UPDATE ON evidence_checklists
    FOR EACH ROW
    EXECUTE FUNCTION update_evidence_checklist_updated_at();

-- Comments for documentation
COMMENT ON TABLE evidence_checklists IS 'Document requirements and satisfaction tracking per transaction. Determines which documents are required and whether they have been uploaded and validated.';

COMMENT ON COLUMN evidence_checklists.transaction_id IS 'Foreign key to transactions. One checklist per transaction.';
COMMENT ON COLUMN evidence_checklists.invoice_required IS 'Whether commercial invoice is required for this transaction';
COMMENT ON COLUMN evidence_checklists.invoice_uploaded IS 'Whether commercial invoice has been uploaded and validated';
COMMENT ON COLUMN evidence_checklists.is_satisfied IS 'Whether all required documents have been uploaded and validated';
COMMENT ON COLUMN evidence_checklists.evaluated_at IS 'Timestamp when checklist satisfaction was last evaluated';