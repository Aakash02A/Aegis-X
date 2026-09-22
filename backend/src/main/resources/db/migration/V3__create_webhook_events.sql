-- Flyway V3: Create webhook_events table for idempotency and audit tracking
CREATE TABLE IF NOT EXISTS webhook_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    event_id VARCHAR(100) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    payment_id VARCHAR(100),
    transaction_reference VARCHAR(64),
    payload TEXT NOT NULL,
    signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    provider_event_timestamp TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_webhook_events_event_id ON webhook_events(event_id);
CREATE INDEX IF NOT EXISTS idx_webhook_events_payment_id ON webhook_events(payment_id);
CREATE INDEX IF NOT EXISTS idx_webhook_events_tx_ref ON webhook_events(transaction_reference);
CREATE INDEX IF NOT EXISTS idx_webhook_events_status ON webhook_events(processing_status);
