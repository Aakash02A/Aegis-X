-- ============================================================================
-- V17: Update Document Types to Match TAS Specification
-- Purpose: Add new document types and update constraints
-- ============================================================================

-- Drop existing document type constraint if it exists
ALTER TABLE documents 
DROP CONSTRAINT IF EXISTS chk_document_type;

-- Add updated document type constraint to include all types from TAS specification
ALTER TABLE documents 
ADD CONSTRAINT chk_document_type CHECK (document_type IN (
    'COMMERCIAL_INVOICE',
    'SHIPPING_BILL', 
    'PACKING_LIST',
    'CERTIFICATE_OF_ORIGIN',
    'INVOICE',
    'SHIPPING_PROOF',
    'EXPORT_DECLARATION',
    'BENEFICIARY_PROOF',
    'OTHER'
));

-- Update existing INVOICE records to use canonical type if desired
-- (Optional - keeping both for backward compatibility)
-- UPDATE documents SET document_type = 'COMMERCIAL_INVOICE' WHERE document_type = 'INVOICE';

-- Add index on document_type for performance
CREATE INDEX IF NOT EXISTS idx_documents_type ON documents(document_type);

-- Add composite index for transaction and document type queries
CREATE INDEX IF NOT EXISTS idx_documents_txn_type ON documents(transaction_id, document_type);

-- Update comments
COMMENT ON COLUMN documents.document_type IS 'Document type according to TAS specification. Supports both core types (COMMERCIAL_INVOICE, SHIPPING_BILL, etc.) and legacy types for backward compatibility.';