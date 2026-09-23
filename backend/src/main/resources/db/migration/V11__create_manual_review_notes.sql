-- V11: Create manual_review_notes table for compliance officer investigation notes
-- Purpose: Allow compliance officers to add investigation notes to transactions
-- Design: Append-only with automatic audit trail integration

CREATE TABLE IF NOT EXISTS manual_review_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note_text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for transaction-based note queries (compliance workspace)
CREATE INDEX idx_review_notes_transaction_id ON manual_review_notes(transaction_id);

-- Index for user-based queries (who wrote what notes)
CREATE INDEX idx_review_notes_user_id ON manual_review_notes(user_id);

-- Index for time-based queries and reporting
CREATE INDEX idx_review_notes_created_at ON manual_review_notes(created_at);

-- Composite index for transaction timeline + user tracking
CREATE INDEX idx_review_notes_transaction_user ON manual_review_notes(transaction_id, created_at DESC);

-- Comments for documentation
COMMENT ON TABLE manual_review_notes IS 
'Immutable compliance officer notes on transactions. Each note represents an investigation step or finding. Notes are append-only and automatically create audit events for traceability. Used by compliance officers during transaction review and for audit trail documentation.';

COMMENT ON COLUMN manual_review_notes.transaction_id IS 
'Foreign key to transactions. Links each note to a specific transaction. Cascade delete if transaction is removed.';

COMMENT ON COLUMN manual_review_notes.user_id IS 
'Foreign key to users. Records which compliance officer or admin wrote the note. Used for RBAC and audit trail.';

COMMENT ON COLUMN manual_review_notes.note_text IS 
'Free-text investigation note. Can contain findings, decisions, recommendations, or follow-up items. No length limit - intended for detailed analysis.';

COMMENT ON COLUMN manual_review_notes.created_at IS 
'Immutable timestamp when note was created. Used for chronological ordering in compliance workspace and audit timeline.';
