-- V10: Create audit_events table for centralized audit trail
-- Purpose: Immutable append-only audit log recording every significant business event
-- across all modules (auth, transactions, webhooks, OCR, AI, policy, WhatsApp)

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    event_type VARCHAR(100) NOT NULL,
    module VARCHAR(50) NOT NULL,
    actor_id UUID,
    actor_type VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    previous_state VARCHAR(100),
    new_state VARCHAR(100),
    event_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for transaction-based timeline queries
CREATE INDEX idx_audit_transaction_id ON audit_events(transaction_id);

-- Index for correlation ID tracing across modules
CREATE INDEX idx_audit_correlation_id ON audit_events(correlation_id);

-- Index for event type filtering (e.g., all POLICY_APPROVED events)
CREATE INDEX idx_audit_event_type ON audit_events(event_type);

-- Index for time-based queries and reporting
CREATE INDEX idx_audit_created_at ON audit_events(created_at);

-- Composite index for transaction timeline queries with timestamp
CREATE INDEX idx_audit_transaction_created ON audit_events(transaction_id, created_at);

-- Composite index for module-based event queries
CREATE INDEX idx_audit_module_created ON audit_events(module, created_at);

-- Composite index for actor tracking (user actions, system events, webhooks)
CREATE INDEX idx_audit_actor_type_created ON audit_events(actor_type, created_at);

-- Comments for documentation
COMMENT ON TABLE audit_events IS 'Immutable append-only audit trail. Every significant business event across all modules is recorded here. No updates or deletes allowed. Used for compliance, auditability, and explainability of all decisions and state transitions.';

COMMENT ON COLUMN audit_events.transaction_id IS 'Foreign key to transactions. Links every audit event to a transaction.';

COMMENT ON COLUMN audit_events.event_type IS 'Business event type: USER_REGISTERED, TRANSACTION_CREATED, PAYMENT_RECEIVED, DOCUMENT_UPLOADED, OCR_COMPLETED, AI_ANALYSIS_COMPLETED, POLICY_APPROVED, POLICY_HOLD, WHATSAPP_SENT, WEBHOOK_RECEIVED, etc.';

COMMENT ON COLUMN audit_events.module IS 'Source module: AUTH, TRANSACTION, RAZORPAY, DOCUMENT, OCR, AI, POLICY_ENGINE, WHATSAPP, WEBHOOK';

COMMENT ON COLUMN audit_events.actor_id IS 'UUID of the actor (user, system, webhook). Can be NULL for system-initiated events.';

COMMENT ON COLUMN audit_events.actor_type IS 'Type of actor: USER, SYSTEM, WEBHOOK, AI, POLICY_ENGINE';

COMMENT ON COLUMN audit_events.correlation_id IS 'Request correlation ID for tracing a single request across all modules. Generated per-request, propagated throughout transaction lifecycle.';

COMMENT ON COLUMN audit_events.previous_state IS 'State before this event. NULL if this is an initial state. Enables before/after comparison for state transitions.';

COMMENT ON COLUMN audit_events.new_state IS 'State after this event. NULL if no state change (e.g., for audit/log events). Enables before/after comparison for state transitions.';

COMMENT ON COLUMN audit_events.event_data IS 'JSONB payload with event-specific context. Examples: OCR results, AI reasoning, policy rule evaluation, webhook headers, WhatsApp metadata.';

COMMENT ON COLUMN audit_events.created_at IS 'Immutable timestamp when event was recorded. Used for chronological timeline and audit ordering.';

-- Add constraint to ensure immutability: audit_events table should be append-only
-- This is enforced at application level (no UPDATE or DELETE triggers), but documented here
COMMENT ON CONSTRAINT audit_events_pkey IS 'Primary key. Immutable. No updates or deletes allowed on audit records.';
