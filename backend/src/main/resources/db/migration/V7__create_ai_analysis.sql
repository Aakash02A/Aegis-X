-- V7__create_ai_analysis.sql
-- Create immutable AI analysis storage for regulatory reasoning findings.
-- Every analysis persists before consumption. Never overwrite previous analyses.

CREATE TABLE IF NOT EXISTS ai_analysis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    extraction_id UUID NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    analysis_json JSONB NOT NULL,
    confidence_score DECIMAL(5, 2) NOT NULL CHECK (confidence_score >= 0 AND confidence_score <= 100),
    processing_duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_analysis_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_analysis_extraction FOREIGN KEY (extraction_id) REFERENCES document_extractions (id) ON DELETE CASCADE
);

-- Indexes for query optimization
CREATE INDEX idx_ai_analysis_transaction_id ON ai_analysis(transaction_id);
CREATE INDEX idx_ai_analysis_extraction_id ON ai_analysis(extraction_id);
CREATE INDEX idx_ai_analysis_created_at ON ai_analysis(created_at DESC);
CREATE INDEX idx_ai_analysis_input_hash ON ai_analysis(input_hash);

-- Immutability constraint: no updates, only inserts
COMMENT ON TABLE ai_analysis IS 'Immutable audit log of AI regulatory reasoning. Each analysis is persisted permanently; no updates or deletes except cascade from parent transaction.';
