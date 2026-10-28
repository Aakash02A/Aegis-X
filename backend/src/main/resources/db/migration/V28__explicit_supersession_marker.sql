-- ============================================================================
-- V28: make supersession an explicit marker
--
-- The MySQL twin of db/migration/V28__explicit_supersession_marker.sql. Both trees advance at the
-- same version from V28 onward; the parity test enforces it. See the PostgreSQL file for the full
-- explanation of the defect and the reasoning.
--
-- In the MySQL baseline (V1) the marker was derived from superseded_by_document_id:
--     active_document_type GENERATED ALWAYS AS
--         (IF(superseded_by_document_id IS NULL, document_type, NULL)) VIRTUAL
-- which reproduced the PostgreSQL partial index faithfully but inherited the same circular
-- dependency, so it must be rebuilt on superseded_at.
--
-- The baseline is append-only, so this is a new migration rather than an edit to V1.
-- ============================================================================

ALTER TABLE pi_evidence_documents ADD COLUMN superseded_at DATETIME(6) NULL;

-- Existing superseded rows were already inactive; give them a timestamp so the rebuilt index sees
-- them exactly as the previous one did.
UPDATE pi_evidence_documents
   SET superseded_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP(6))
 WHERE superseded_by_document_id IS NOT NULL
   AND superseded_at IS NULL;

-- The index must go before its generated column can be replaced.
DROP INDEX uq_pi_evidence_doc_active_type ON pi_evidence_documents;

ALTER TABLE pi_evidence_documents DROP COLUMN active_document_type;

-- Rebuilt on the explicit marker. Semantics are unchanged: the column is the document type for an
-- active row and NULL for a superseded one, and a unique index ignores NULL key parts, so any number
-- of superseded rows may share an (order, document_type) while only one active row may.
ALTER TABLE pi_evidence_documents
    ADD COLUMN active_document_type VARCHAR(50) COLLATE utf8mb4_0900_as_cs
        GENERATED ALWAYS AS (IF(superseded_at IS NULL, document_type, NULL)) VIRTUAL;

CREATE UNIQUE INDEX uq_pi_evidence_doc_active_type
    ON pi_evidence_documents(order_id, active_document_type);

-- The PostgreSQL twin also adds:
--
--   CONSTRAINT chk_pi_evidence_doc_supersede_marker
--       CHECK (superseded_at IS NOT NULL OR superseded_by_document_id IS NULL)
--
-- MySQL cannot have it, and the refusal is measured rather than assumed:
--
--   ERROR 3823: Column 'superseded_by_document_id' cannot be used in a check constraint
--   'chk_pi_evidence_doc_supersede_marker': needed in a foreign key constraint
--   'fk_pi_evidence_doc_superseded_by'
--
-- MySQL forbids a column that participates in a foreign key from appearing in a CHECK when that
-- foreign key has a referential action (here ON DELETE SET NULL), because the action may mutate the
-- column. This is a deliberate, documented engine difference, not an omitted requirement: the CHECK
-- is an auxiliary consistency guard introduced by this migration, the invariant it protects is
-- maintained by EvidenceDocumentService (which always writes superseded_at before naming a
-- replacement, inside one transaction), and the load-bearing uniqueness invariant is enforced here
-- exactly as it is on PostgreSQL. Making MySQL match would mean changing the foreign key's delete
-- action, which would be weakening the referential guarantee to satisfy a constraint that is merely
-- additive. Verified by MySqlConstraintParityTest.
