-- V6__create_document_extractions.sql
-- Phase 08: Document Extraction Storage
-- Separates raw OCR output from future AI analysis
-- Allows versioning and reprocessing without losing original extraction

CREATE TABLE document_extractions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    extracted_text TEXT,
    extracted_data JSONB,
    extraction_method VARCHAR(50) NOT NULL,
    confidence_score DECIMAL(5, 2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index on document_id for fast lookups of extractions for a document
CREATE INDEX idx_document_extractions_document_id ON document_extractions(document_id);

-- Index on extraction_method for querying by method (TIKA, TESSERACT, etc)
CREATE INDEX idx_document_extractions_extraction_method ON document_extractions(extraction_method);

-- Index on confidence_score for querying high/low confidence extractions
CREATE INDEX idx_document_extractions_confidence_score ON document_extractions(confidence_score);

-- Index on created_at for temporal queries
CREATE INDEX idx_document_extractions_created_at ON document_extractions(created_at);
