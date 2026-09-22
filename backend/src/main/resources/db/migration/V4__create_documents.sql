-- Flyway V4: Create documents and required_documents tables for Evidence Collection

CREATE TABLE IF NOT EXISTS documents (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    document_type   VARCHAR(50)  NOT NULL,
    document_status VARCHAR(50)  NOT NULL DEFAULT 'UPLOADED',
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    file_size       BIGINT       NOT NULL,
    file_hash       VARCHAR(128),
    storage_path    VARCHAR(512) NOT NULL,
    uploaded_by     UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_documents_transaction_id ON documents(transaction_id);
CREATE INDEX IF NOT EXISTS idx_documents_transaction_type ON documents(transaction_id, document_type);

CREATE TABLE IF NOT EXISTS required_documents (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    document_type   VARCHAR(50)  NOT NULL,
    fulfilled       BOOLEAN      NOT NULL DEFAULT FALSE,
    document_id     UUID         REFERENCES documents(id),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_required_doc_tx_type UNIQUE (transaction_id, document_type)
);

CREATE INDEX IF NOT EXISTS idx_required_documents_transaction_id ON required_documents(transaction_id);
