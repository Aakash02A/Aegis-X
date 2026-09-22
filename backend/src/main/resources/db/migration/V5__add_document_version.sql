-- Flyway V5: Add optimistic locking to documents for concurrent processing updates
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;