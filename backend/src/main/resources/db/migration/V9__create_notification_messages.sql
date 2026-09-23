-- V9: Create notification_messages table for WhatsApp and external integrations
-- Purpose: Immutable communication history for compliance audit trail

CREATE TABLE IF NOT EXISTS notification_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    channel VARCHAR(50) NOT NULL DEFAULT 'WHATSAPP',
    direction VARCHAR(20) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    template_name VARCHAR(255),
    message_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'SENT',
    delivery_timestamp TIMESTAMP,
    read_timestamp TIMESTAMP,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for transaction queries
CREATE INDEX idx_notification_transaction_id ON notification_messages(transaction_id);

-- Index for message tracking
CREATE INDEX idx_notification_message_id ON notification_messages(message_id);

-- Index for recipient lookup
CREATE INDEX idx_notification_recipient ON notification_messages(recipient);

-- Index for status queries and reporting
CREATE INDEX idx_notification_status_created ON notification_messages(status, created_at);

-- Composite index for transaction communication history
CREATE INDEX idx_notification_transaction_direction ON notification_messages(transaction_id, direction);

-- Comment for audit trail
COMMENT ON TABLE notification_messages IS 'Immutable communication history for WhatsApp and external integrations. All inbound and outbound messages recorded for compliance audit trail.';

COMMENT ON COLUMN notification_messages.direction IS 'INBOUND (webhook received) or OUTBOUND (message sent to user)';

COMMENT ON COLUMN notification_messages.status IS 'SENT, DELIVERED, READ, FAILED - never overwritten, only appended with updates';

COMMENT ON COLUMN notification_messages.template_name IS 'WhatsApp template identifier for outbound messages';

COMMENT ON COLUMN notification_messages.message_id IS 'Meta WhatsApp message ID for idempotency and tracking';

COMMENT ON COLUMN notification_messages.failure_reason IS 'Reason for failed delivery, e.g., invalid phone number, expired media';
