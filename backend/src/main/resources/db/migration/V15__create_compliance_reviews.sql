-- ============================================================================
-- V15: Create Compliance Reviews Table
-- Purpose: Human decision log for compliance officer manual reviews
-- According to TAS Section 6 - Complete Database Schema  
-- ============================================================================

CREATE TABLE compliance_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    reviewer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    previous_state VARCHAR(40) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    justification TEXT NOT NULL,
    review_context JSONB DEFAULT '{}'::jsonb,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_compliance_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT chk_compliance_previous_state CHECK (previous_state = 'COMPLIANCE_HOLD'),
    CONSTRAINT chk_compliance_justification_length CHECK (char_length(justification) >= 10)
);

-- Indexes for query optimization  
CREATE INDEX idx_compliance_reviews_txn_id ON compliance_reviews(transaction_id);
CREATE INDEX idx_compliance_reviews_reviewer_id ON compliance_reviews(reviewer_id);
CREATE INDEX idx_compliance_reviews_decision ON compliance_reviews(decision);
CREATE INDEX idx_compliance_reviews_reviewed_at ON compliance_reviews(reviewed_at);

-- Composite index for reviewer performance analysis
CREATE INDEX idx_compliance_reviews_reviewer_decision ON compliance_reviews(reviewer_id, decision);

-- Composite index for transaction review timeline
CREATE INDEX idx_compliance_reviews_txn_reviewed_at ON compliance_reviews(transaction_id, reviewed_at);

-- Comments for documentation
COMMENT ON TABLE compliance_reviews IS 'Human decision log for compliance officer manual reviews. Records all manual approvals and rejections with mandatory justification.';

COMMENT ON COLUMN compliance_reviews.transaction_id IS 'Foreign key to transactions. Links review to the transaction being reviewed.';
COMMENT ON COLUMN compliance_reviews.reviewer_id IS 'Foreign key to users. Must be a user with COMPLIANCE_OFFICER role.';
COMMENT ON COLUMN compliance_reviews.previous_state IS 'Transaction state before review. Must be COMPLIANCE_HOLD.';
COMMENT ON COLUMN compliance_reviews.decision IS 'Review outcome: APPROVED or REJECTED';
COMMENT ON COLUMN compliance_reviews.justification IS 'Mandatory explanation for the review decision. Minimum 10 characters.';
COMMENT ON COLUMN compliance_reviews.review_context IS 'Additional context data for the review (policy violations, documents reviewed, etc.)';
COMMENT ON COLUMN compliance_reviews.reviewed_at IS 'Timestamp when the review decision was made';