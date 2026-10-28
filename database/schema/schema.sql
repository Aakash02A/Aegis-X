-- =============================================================================
-- Aegis-X — MySQL 8.4 baseline (V1)
--
-- This single migration is the MySQL equivalent of the frozen PostgreSQL history
-- db/migration/V1..V27. It exists because editing those 27 files would break the
-- Flyway checksums of every database that has already applied them.
--
-- PROVENANCE AND RULES
--   * Derived from db/migration/V1..V27, which remain byte-identical and are still
--     the PostgreSQL history. Nothing here is invented; each object traces to a
--     PostgreSQL migration.
--   * From V28 onward BOTH trees advance together at the same version
--     (db/migration/V28__x.sql and db/mysql/migration/V28__x.sql). A test enforces it.
--   * No MySQL deployment exists yet, so nothing is skipped by squashing V1..V27.
--
-- TRANSLATION RULES APPLIED (see docs/POSTGRESQL_TO_MYSQL_MIGRATION.md)
--   1. UUID            -> CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
--                         with hibernate.type.preferred_uuid_jdbc_type=CHAR.
--   2. TIMESTAMP/TIMESTAMPTZ -> DATETIME(6). Never MySQL TIMESTAMP (session-time-zone
--                         conversion, 2038 limit). The JDBC session is pinned to UTC,
--                         so DEFAULT CURRENT_TIMESTAMP still means UTC as it does today.
--   3. BOOLEAN         -> BIT(1), which is what Hibernate 6.5.3's MySQLDialect expects
--                         (verified: getPreferredSqlTypeCodeForBoolean() = BIT).
--                         DEFAULT FALSE/TRUE -> DEFAULT b'0'/b'1'.
--   4. JSONB           -> JSON ; DEFAULT '{}'::jsonb -> DEFAULT (JSON_OBJECT()).
--   5. Identifier columns that take part in a UNIQUE constraint or an equality lookup
--                         are case-SENSITIVE (utf8mb4_0900_as_cs, or ascii_bin for
--                         hashes and UUIDs). The server default utf8mb4_0900_ai_ci is
--                         case-insensitive and would change uniqueness semantics.
--   6. Regex CHECK (~) -> REGEXP_LIKE(col, pattern, 'c'). The 'c' flag is mandatory:
--                         without it a case-insensitive collation accepts 'inr'.
--   7. Partial indexes -> plain indexes, except the uniqueness guards:
--                         * the four "provider id IS NOT NULL" guards port directly to
--                           plain UNIQUE indexes, because MySQL already treats NULLs as
--                           non-colliding;
--                         * uq_pi_evidence_doc_active_type cannot be expressed that way
--                           and is reproduced with a STORED generated column that is NULL
--                           for superseded rows. See the pi_evidence_documents block;
--                         * idx_settlements_manual_intervention is reproduced the same way,
--                           because without its predicate it would be an exact duplicate of
--                           idx_settlements_initiated_at and MySQL 8.4 rejects duplicate
--                           indexes (error 1831). See the settlements block.
--   8. COMMENT ON      -> inline COMMENT on the column / table COMMENT=. A constraint
--                         comment (V10) has no MySQL equivalent and is recorded as a
--                         deliberate, documented loss.
--   9. IF NOT EXISTS   -> dropped (unsupported by MySQL 8 for indexes and added columns);
--                         the baseline creates each object exactly once.
--  10. gen_random_uuid() -> dropped. BaseEntity assigns UUIDs in Java; keeping a second
--                         source of ids would be worse than having one.
--  11. PL/pgSQL updated_at triggers (V13, V16) -> not ported. BaseEntity.@PreUpdate owns
--                         updated_at; the triggers were a PostgreSQL-only convenience.
--  12. Data-only statements from V12/V18/V19 (backfilling is_terminal, verdict) -> not
--                         ported: a fresh schema has no rows to backfill, and the CHECK
--                         constraints are declared in their FINAL (V19/V27) form.
--  13. Inline unnamed CHECK constraints are given explicit names so the parity test can
--                         assert them and so MySQL's per-schema uniqueness rule is met.
--  14. One redundant PostgreSQL index is deliberately NOT carried over:
--                         idx_documents_txn_type (V17) is identical to
--                         idx_documents_transaction_type (V4). MySQL deprecates duplicate
--                         indexes (error 1831) and will disallow them, so keeping both would
--                         break a future MySQL upgrade. No access path is lost. See the
--                         documents block.
--
-- Amounts are integer minor units (BIGINT) or exact DECIMAL. No floating point money.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Reference: audit event types (V16)
-- -----------------------------------------------------------------------------
CREATE TABLE audit_event_types (
    event_type   VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    description  VARCHAR(500) NOT NULL,
    module       VARCHAR(50)  NOT NULL,
    PRIMARY KEY (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Reference list of audit event types.';

INSERT INTO audit_event_types (event_type, description, module) VALUES
('SETTLEMENT_INITIATED', 'Settlement request initiated', 'SETTLEMENT'),
('SETTLEMENT_SUBMITTED', 'Settlement submitted to processor', 'SETTLEMENT'),
('SETTLEMENT_COMPLETED', 'Settlement completed successfully', 'SETTLEMENT'),
('SETTLEMENT_FAILED', 'Settlement processing failed', 'SETTLEMENT'),
('SETTLEMENT_CANCELLED', 'Settlement cancelled', 'SETTLEMENT'),
('SETTLEMENT_RETRY', 'Settlement retry attempted', 'SETTLEMENT'),
('SETTLEMENT_RECONCILED', 'Settlement reconciliation completed', 'SETTLEMENT'),
('SETTLEMENT_STATUS_UPDATED', 'Settlement status updated', 'SETTLEMENT'),
('SETTLEMENT_VIEW', 'Settlement details viewed', 'SETTLEMENT')
-- PostgreSQL used ON CONFLICT (event_type) DO NOTHING.
-- The row alias form (8.0.19+) is used rather than VALUES(col), which MySQL 8.4 reports as
-- deprecated (error 1287) and will remove.
AS new_row
ON DUPLICATE KEY UPDATE description = new_row.description;

-- -----------------------------------------------------------------------------
-- Auth (V1)
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    full_name      VARCHAR(120) NOT NULL,
    -- Email is a UNIQUE key: case-sensitive to match PostgreSQL text equality.
    email          VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    -- `role` is a reserved word in MySQL 8 and is quoted wherever it appears.
    `role`         VARCHAR(50)  NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_users_email ON users(email);

-- -----------------------------------------------------------------------------
-- Transactions (V2, V12, V19)
-- `is_terminal` comes from V12; `chk_txn_status` is the FINAL V19 form.
-- -----------------------------------------------------------------------------
CREATE TABLE transactions (
    id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_number  VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    merchant_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount            DECIMAL(15,2) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    beneficiary_name  VARCHAR(255) NOT NULL,
    purpose_code      VARCHAR(50)  NOT NULL,
    status            VARCHAR(50)  NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    is_terminal       BIT(1) NOT NULL DEFAULT b'0',
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_transactions_reference_number UNIQUE (reference_number),
    CONSTRAINT fk_transactions_merchant FOREIGN KEY (merchant_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_txn_status CHECK (status IN (
        'CREATED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'EVIDENCE_PENDING',
        'OCR_PROCESSING', 'OCR_FAILED', 'AI_ANALYSIS', 'AI_ANALYSIS_FAILED',
        'POLICY_EVALUATION', 'APPROVED', 'COMPLIANCE_HOLD', 'REJECTED',
        'SETTLEMENT_PENDING', 'SETTLED', 'SETTLEMENT_FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Cross-border transaction under review.';

CREATE INDEX idx_transactions_reference_number ON transactions(reference_number);
CREATE INDEX idx_transactions_merchant_id ON transactions(merchant_id);
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_is_terminal ON transactions(is_terminal);
CREATE INDEX idx_transactions_status_terminal ON transactions(status, is_terminal);

-- -----------------------------------------------------------------------------
-- Documents and required documents (V4, V5, V17)
-- -----------------------------------------------------------------------------
CREATE TABLE documents (
    id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_type     VARCHAR(50) NOT NULL,
    document_status   VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    original_filename VARCHAR(255) NOT NULL,
    stored_filename   VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    file_size         BIGINT NOT NULL,
    file_hash         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin,
    storage_path      VARCHAR(512) NOT NULL,
    uploaded_by       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_documents_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_document_type CHECK (document_type IN (
        'COMMERCIAL_INVOICE', 'SHIPPING_BILL', 'PACKING_LIST', 'CERTIFICATE_OF_ORIGIN',
        'INVOICE', 'SHIPPING_PROOF', 'EXPORT_DECLARATION', 'BENEFICIARY_PROOF', 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_documents_transaction_id ON documents(transaction_id);
CREATE INDEX idx_documents_transaction_type ON documents(transaction_id, document_type);
CREATE INDEX idx_documents_type ON documents(document_type);

-- PostgreSQL V17 also creates idx_documents_txn_type on documents(transaction_id, document_type),
-- which is byte-identical to idx_documents_transaction_type created by V4. PostgreSQL tolerates
-- the redundancy silently; MySQL 8.4 raises "Duplicate index 'idx_documents_txn_type' defined on
-- the table 'documents' ... deprecated and will be disallowed in a future release" (error 1831), so
-- a future MySQL upgrade would refuse to apply this baseline. The identical index is kept under its
-- original name (idx_documents_transaction_type), so no query loses an access path. This is a
-- deliberate, documented divergence: the MySQL tree omits a redundant index, it does not drop a
-- distinct one.

CREATE TABLE required_documents (
    id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_type   VARCHAR(50) NOT NULL,
    fulfilled       BIT(1) NOT NULL DEFAULT b'0',
    document_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_required_doc_tx_type UNIQUE (transaction_id, document_type),
    CONSTRAINT fk_required_documents_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_required_documents_document FOREIGN KEY (document_id) REFERENCES documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_required_documents_transaction_id ON required_documents(transaction_id);

-- -----------------------------------------------------------------------------
-- Document extractions (V6, V25)
-- confidence_score is DOUBLE, not DECIMAL: V25 aligned it with the Double field.
-- -----------------------------------------------------------------------------
CREATE TABLE document_extractions (
    id                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    extracted_text        TEXT,
    extracted_data        JSON,
    extraction_method     VARCHAR(50) NOT NULL,
    confidence_score      DOUBLE,
    processing_duration_ms BIGINT,
    ocr_language          VARCHAR(10),
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_document_extractions_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_document_extractions_document_id ON document_extractions(document_id);
CREATE INDEX idx_document_extractions_extraction_method ON document_extractions(extraction_method);
CREATE INDEX idx_document_extractions_confidence_score ON document_extractions(confidence_score);
CREATE INDEX idx_document_extractions_created_at ON document_extractions(created_at);

-- -----------------------------------------------------------------------------
-- AI analysis (V7)
-- -----------------------------------------------------------------------------
CREATE TABLE ai_analysis (
    id                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    extraction_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_name            VARCHAR(255) NOT NULL,
    prompt_version        VARCHAR(50)  NOT NULL,
    input_hash            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_json         JSON NOT NULL,
    confidence_score      DECIMAL(5,2) NOT NULL,
    processing_duration_ms BIGINT NOT NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_analysis_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_analysis_extraction FOREIGN KEY (extraction_id) REFERENCES document_extractions(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_analysis_confidence CHECK (confidence_score >= 0 AND confidence_score <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable audit log of AI regulatory reasoning. Each analysis is persisted permanently.';

CREATE INDEX idx_ai_analysis_transaction_id ON ai_analysis(transaction_id);
CREATE INDEX idx_ai_analysis_extraction_id ON ai_analysis(extraction_id);
CREATE INDEX idx_ai_analysis_created_at ON ai_analysis(created_at DESC);
CREATE INDEX idx_ai_analysis_input_hash ON ai_analysis(input_hash);

-- -----------------------------------------------------------------------------
-- Policy evaluations (V8, V18, V24)
-- V18's added columns and V24's updated_at are declared inline here.
-- -----------------------------------------------------------------------------
CREATE TABLE policy_evaluations (
    id                     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ai_analysis_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    final_decision         VARCHAR(50) NOT NULL,
    overall_score          DECIMAL(5,2),
    evaluation_json        JSON NOT NULL,
    processing_duration_ms BIGINT NOT NULL,
    verdict                VARCHAR(20),
    total_rules_count      INTEGER,
    passed_rules_count     INTEGER,
    failed_rules_count     INTEGER,
    engine_version         VARCHAR(20) DEFAULT '1.0.0',
    evaluated_at           DATETIME(6),
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_policy_eval_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_policy_eval_ai_analysis FOREIGN KEY (ai_analysis_id) REFERENCES ai_analysis(id) ON DELETE SET NULL,
    CONSTRAINT fk_policy_eval_transaction_new FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT chk_policy_final_decision CHECK (final_decision IN ('APPROVED', 'COMPLIANCE_HOLD')),
    CONSTRAINT chk_policy_verdict CHECK (verdict IN ('APPROVED', 'HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_policy_eval_transaction_id ON policy_evaluations(transaction_id);
CREATE INDEX idx_policy_eval_created_at ON policy_evaluations(created_at DESC);
CREATE INDEX idx_policy_eval_final_decision ON policy_evaluations(final_decision);
CREATE INDEX idx_policy_eval_verdict ON policy_evaluations(verdict);
CREATE INDEX idx_policy_eval_evaluated_at ON policy_evaluations(evaluated_at);
CREATE INDEX idx_policy_eval_engine_version ON policy_evaluations(engine_version);
CREATE INDEX idx_policy_eval_txn_verdict ON policy_evaluations(transaction_id, verdict);

-- -----------------------------------------------------------------------------
-- Policy violations (V14)
-- rule_id regex CHECK becomes REGEXP_LIKE with the case-sensitive 'c' flag.
-- -----------------------------------------------------------------------------
CREATE TABLE policy_violations (
    id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_evaluation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rule_id              VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    rule_name            VARCHAR(100) NOT NULL,
    severity             VARCHAR(20) NOT NULL,
    description          TEXT NOT NULL,
    rule_context         JSON DEFAULT (JSON_OBJECT()),
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_policy_violations_evaluation FOREIGN KEY (policy_evaluation_id) REFERENCES policy_evaluations(id) ON DELETE CASCADE,
    CONSTRAINT chk_violation_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_violation_rule_id CHECK (REGEXP_LIKE(rule_id, '^PR-[0-9]{2}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_policy_violations_eval_id ON policy_violations(policy_evaluation_id);
CREATE INDEX idx_policy_violations_rule_id ON policy_violations(rule_id);
CREATE INDEX idx_policy_violations_severity ON policy_violations(severity);
CREATE INDEX idx_policy_violations_created_at ON policy_violations(created_at);
CREATE INDEX idx_policy_violations_rule_severity ON policy_violations(rule_id, severity);

-- -----------------------------------------------------------------------------
-- Compliance reviews (V15, V24)
-- -----------------------------------------------------------------------------
CREATE TABLE compliance_reviews (
    id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reviewer_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state   VARCHAR(40) NOT NULL,
    decision         VARCHAR(20) NOT NULL,
    justification    TEXT NOT NULL,
    review_context   JSON DEFAULT (JSON_OBJECT()),
    reviewed_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_compliance_reviews_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_compliance_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_compliance_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT chk_compliance_previous_state CHECK (previous_state = 'COMPLIANCE_HOLD'),
    CONSTRAINT chk_compliance_justification_length CHECK (CHAR_LENGTH(justification) >= 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Human decision log for compliance officer manual reviews.';

CREATE INDEX idx_compliance_reviews_txn_id ON compliance_reviews(transaction_id);
CREATE INDEX idx_compliance_reviews_reviewer_id ON compliance_reviews(reviewer_id);
CREATE INDEX idx_compliance_reviews_decision ON compliance_reviews(decision);
CREATE INDEX idx_compliance_reviews_reviewed_at ON compliance_reviews(reviewed_at);
CREATE INDEX idx_compliance_reviews_reviewer_decision ON compliance_reviews(reviewer_id, decision);
CREATE INDEX idx_compliance_reviews_txn_reviewed_at ON compliance_reviews(transaction_id, reviewed_at);

-- -----------------------------------------------------------------------------
-- Evidence checklists (V13)
-- -----------------------------------------------------------------------------
CREATE TABLE evidence_checklists (
    id                        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invoice_required          BIT(1) NOT NULL DEFAULT b'1',
    invoice_uploaded          BIT(1) NOT NULL DEFAULT b'0',
    shipping_bill_required    BIT(1) NOT NULL DEFAULT b'0',
    shipping_bill_uploaded    BIT(1) NOT NULL DEFAULT b'0',
    packing_list_required     BIT(1) NOT NULL DEFAULT b'0',
    packing_list_uploaded     BIT(1) NOT NULL DEFAULT b'0',
    certificate_origin_required BIT(1) NOT NULL DEFAULT b'0',
    certificate_origin_uploaded BIT(1) NOT NULL DEFAULT b'0',
    is_satisfied              BIT(1) NOT NULL DEFAULT b'0',
    evaluated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_evidence_checklists_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_evidence_checklists_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Document requirements and satisfaction tracking per transaction.';

CREATE INDEX idx_evidence_checklist_txn_id ON evidence_checklists(transaction_id);
CREATE INDEX idx_evidence_checklist_satisfied ON evidence_checklists(is_satisfied);
CREATE INDEX idx_evidence_checklist_evaluated_at ON evidence_checklists(evaluated_at);

-- -----------------------------------------------------------------------------
-- Manual review notes (V11)
-- -----------------------------------------------------------------------------
CREATE TABLE manual_review_notes (
    id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note_text      TEXT NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_review_notes_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable compliance officer notes on transactions. Append-only.';

CREATE INDEX idx_review_notes_transaction_id ON manual_review_notes(transaction_id);
CREATE INDEX idx_review_notes_user_id ON manual_review_notes(user_id);
CREATE INDEX idx_review_notes_created_at ON manual_review_notes(created_at);
CREATE INDEX idx_review_notes_transaction_user ON manual_review_notes(transaction_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Audit events (V10)
-- The PostgreSQL COMMENT ON CONSTRAINT (audit_events_pkey) has no MySQL equivalent
-- and is not reproduced; see the header.
-- -----------------------------------------------------------------------------
CREATE TABLE audit_events (
    id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type     VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    module         VARCHAR(50) NOT NULL,
    actor_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_type     VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    previous_state VARCHAR(100),
    new_state      VARCHAR(100),
    event_data     JSON,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable append-only audit trail across all modules. No updates or deletes.';

CREATE INDEX idx_audit_transaction_id ON audit_events(transaction_id);
CREATE INDEX idx_audit_correlation_id ON audit_events(correlation_id);
CREATE INDEX idx_audit_event_type ON audit_events(event_type);
CREATE INDEX idx_audit_created_at ON audit_events(created_at);
CREATE INDEX idx_audit_transaction_created ON audit_events(transaction_id, created_at);
CREATE INDEX idx_audit_module_created ON audit_events(module, created_at);
CREATE INDEX idx_audit_actor_type_created ON audit_events(actor_type, created_at);

-- -----------------------------------------------------------------------------
-- Settlements (V16)
-- The two regex currency checks use REGEXP_LIKE with the case-sensitive flag.
-- The five partial indexes were query-plan only; they are plain indexes here. The
-- updated_at trigger is not ported.
-- -----------------------------------------------------------------------------
CREATE TABLE settlements (
    id                     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    settlement_reference   VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    payment_processor      VARCHAR(50) NOT NULL,
    processor_transaction_id VARCHAR(100),
    status                 VARCHAR(50) NOT NULL,
    settlement_amount      DECIMAL(15,2) NOT NULL,
    settlement_currency    VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    exchange_rate          DECIMAL(10,6),
    processing_fees        DECIMAL(10,2),
    initiated_at           DATETIME(6) NOT NULL,
    submitted_at           DATETIME(6),
    completed_at           DATETIME(6),
    expected_completion_at DATETIME(6),
    failure_reason         VARCHAR(500),
    retry_count            INTEGER NOT NULL DEFAULT 0,
    max_retries            INTEGER NOT NULL DEFAULT 3,
    next_retry_at          DATETIME(6),
    processor_metadata     JSON,
    reconciliation_data    JSON,
    reconciled_at          DATETIME(6),
    initiated_by           VARCHAR(100),
    created_at             DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_settlements_reference UNIQUE (settlement_reference),
    CONSTRAINT fk_settlements_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT chk_settlement_amount_positive CHECK (settlement_amount > 0),
    CONSTRAINT chk_settlement_currency_format CHECK (REGEXP_LIKE(settlement_currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT chk_exchange_rate_positive CHECK (exchange_rate IS NULL OR exchange_rate > 0),
    CONSTRAINT chk_processing_fees_non_negative CHECK (processing_fees IS NULL OR processing_fees >= 0),
    CONSTRAINT chk_retry_count_non_negative CHECK (retry_count >= 0),
    CONSTRAINT chk_max_retries_positive CHECK (max_retries > 0),
    CONSTRAINT chk_retry_count_within_max CHECK (retry_count <= max_retries),
    CONSTRAINT chk_status_valid CHECK (status IN (
        'INITIATED', 'PROCESSING', 'SUBMITTED', 'PENDING', 'COMPLETED',
        'FAILED', 'REJECTED', 'CANCELLED', 'MANUAL_REVIEW',
        'RECONCILING', 'RECONCILED', 'RECONCILIATION_FAILED')),
    CONSTRAINT chk_payment_processor_valid CHECK (payment_processor IN (
        'SWIFT', 'RIPPLE', 'VISA_DIRECT', 'MASTERCARD_SEND',
        'ACH', 'SEPA', 'FASTER_PAYMENTS', 'MOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Settlement records for transaction processing.';

CREATE INDEX idx_settlements_transaction_id ON settlements(transaction_id);
CREATE INDEX idx_settlements_status ON settlements(status);
CREATE INDEX idx_settlements_processor ON settlements(payment_processor);
CREATE INDEX idx_settlements_initiated_at ON settlements(initiated_at);
CREATE INDEX idx_settlements_reference ON settlements(settlement_reference);
CREATE INDEX idx_settlements_processor_txn_id ON settlements(processor_transaction_id);
CREATE INDEX idx_settlements_status_retry ON settlements(status, next_retry_at);
CREATE INDEX idx_settlements_overdue ON settlements(expected_completion_at, status);
CREATE INDEX idx_settlements_reconciliation ON settlements(status, reconciled_at);
CREATE INDEX idx_settlements_pending_retry ON settlements(next_retry_at);

-- PostgreSQL V16 declares, for the manual-intervention work queue:
--     CREATE INDEX idx_settlements_manual_intervention ON settlements(initiated_at)
--         WHERE status IN ('MANUAL_REVIEW', 'RECONCILIATION_FAILED');
-- MySQL has no partial indexes. Dropping the predicate would make this index byte-identical to
-- idx_settlements_initiated_at, which is both redundant and a deprecation warning today
-- (error 1831) and an error in a future MySQL release. Instead the predicate is moved into the key
-- with a STORED generated column — the same technique rule 7 uses for the evidence uniqueness guard,
-- and the only translation that keeps the index selective AND non-duplicate.
ALTER TABLE settlements
    ADD COLUMN manual_intervention_at DATETIME(6)
        GENERATED ALWAYS AS (IF(status IN ('MANUAL_REVIEW', 'RECONCILIATION_FAILED'), initiated_at, NULL))
        STORED
        COMMENT 'PostgreSQL partial-index predicate for idx_settlements_manual_intervention.';

CREATE INDEX idx_settlements_manual_intervention ON settlements(manual_intervention_at);

-- -----------------------------------------------------------------------------
-- Notification messages (V9)
-- -----------------------------------------------------------------------------
CREATE TABLE notification_messages (
    id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel            VARCHAR(50) NOT NULL DEFAULT 'WHATSAPP',
    direction          VARCHAR(20) NOT NULL,
    recipient          VARCHAR(255) NOT NULL,
    template_name      VARCHAR(255),
    message_id         VARCHAR(255),
    status             VARCHAR(50) NOT NULL DEFAULT 'SENT',
    delivery_timestamp DATETIME(6),
    read_timestamp     DATETIME(6),
    failure_reason     VARCHAR(500),
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_messages_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable communication history for WhatsApp and external integrations.';

CREATE INDEX idx_notification_transaction_id ON notification_messages(transaction_id);
CREATE INDEX idx_notification_message_id ON notification_messages(message_id);
CREATE INDEX idx_notification_recipient ON notification_messages(recipient);
CREATE INDEX idx_notification_status_created ON notification_messages(status, created_at);
CREATE INDEX idx_notification_transaction_direction ON notification_messages(transaction_id, direction);

-- -----------------------------------------------------------------------------
-- Webhook events (V3)
-- -----------------------------------------------------------------------------
CREATE TABLE webhook_events (
    id                       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider                 VARCHAR(50) NOT NULL,
    event_id                 VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    event_type               VARCHAR(100) NOT NULL,
    payment_id               VARCHAR(100),
    transaction_reference    VARCHAR(64),
    payload                  TEXT NOT NULL,
    signature_verified       BIT(1) NOT NULL DEFAULT b'0',
    processing_status        VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    provider_event_timestamp DATETIME(6),
    created_at               DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_webhook_events_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_webhook_events_event_id ON webhook_events(event_id);
CREATE INDEX idx_webhook_events_payment_id ON webhook_events(payment_id);
CREATE INDEX idx_webhook_events_tx_ref ON webhook_events(transaction_reference);
CREATE INDEX idx_webhook_events_status ON webhook_events(processing_status);

-- =============================================================================
-- Payment Integrity Engine (V20..V27)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Workspaces, orders, payments, refunds, transfers (V20)
-- The four "provider id IS NOT NULL" unique guards port directly: MySQL already
-- treats NULLs as non-colliding in a unique index, which is exactly the predicate.
-- -----------------------------------------------------------------------------
CREATE TABLE pi_workspaces (
    id                         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name                       VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    environment                VARCHAR(20) NOT NULL,
    provider_mode              VARCHAR(30) NOT NULL,
    provider_key_id_masked     VARCHAR(40),
    provider_configured        BIT(1) NOT NULL DEFAULT b'0',
    transfer_capability        VARCHAR(20) NOT NULL DEFAULT 'NOT_CONFIGURED',
    default_currency           VARCHAR(3) NOT NULL DEFAULT 'INR',
    staleness_threshold_seconds BIGINT NOT NULL DEFAULT 900,
    created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_workspaces_name UNIQUE (name),
    CONSTRAINT chk_pi_workspaces_environment CHECK (environment IN ('TEST')),
    CONSTRAINT chk_pi_workspaces_provider_mode CHECK (provider_mode IN ('RAZORPAY_TEST', 'SIMULATED')),
    CONSTRAINT chk_pi_workspaces_threshold CHECK (staleness_threshold_seconds >= 30)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pi_orders (
    id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    internal_reference   VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    provider_order_id    VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
    expected_amount_minor BIGINT NOT NULL,
    currency             VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    description          VARCHAR(255),
    provider_state       VARCHAR(30),
    state                VARCHAR(30) NOT NULL,
    simulated            BIT(1) NOT NULL DEFAULT b'0',
    correlation_id       VARCHAR(64),
    created_by           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_observed_at     DATETIME(6),
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_orders_reference UNIQUE (internal_reference),
    CONSTRAINT uq_pi_orders_provider_order UNIQUE (workspace_id, provider_order_id),
    CONSTRAINT fk_pi_orders_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_orders_amount_positive CHECK (expected_amount_minor > 0),
    CONSTRAINT chk_pi_orders_currency_format CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT chk_pi_orders_state CHECK (state IN ('CREATED', 'ATTEMPTED', 'PAID', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_pi_orders_provider_state CHECK (provider_state IS NULL OR provider_state IN
        ('CREATED', 'ATTEMPTED', 'PAID', 'EXPIRED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Internal order registry. expected_amount_minor is integer minor currency units.';

CREATE INDEX idx_pi_orders_workspace_created ON pi_orders(workspace_id, created_at);
CREATE INDEX idx_pi_orders_state ON pi_orders(state);

CREATE TABLE pi_payments (
    id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_payment_id  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
    method               VARCHAR(40),
    amount_minor         BIGINT NOT NULL,
    currency             VARCHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    provider_state       VARCHAR(30) NOT NULL,
    state                VARCHAR(30) NOT NULL,
    state_evidence_rank  INTEGER NOT NULL DEFAULT 0,
    simulated            BIT(1) NOT NULL DEFAULT b'0',
    correlation_id       VARCHAR(64),
    first_observed_at    DATETIME(6),
    last_observed_at     DATETIME(6),
    failure_reason       VARCHAR(255),
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_payments_provider_payment UNIQUE (workspace_id, provider_payment_id),
    CONSTRAINT fk_pi_payments_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_payments_order FOREIGN KEY (order_id) REFERENCES pi_orders(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_payments_amount_non_negative CHECK (amount_minor >= 0),
    CONSTRAINT chk_pi_payments_currency_format CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')),
    CONSTRAINT chk_pi_payments_state CHECK (state IN
        ('CREATED', 'AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED', 'TIMEOUT_UNKNOWN')),
    CONSTRAINT chk_pi_payments_provider_state CHECK (provider_state IN
        ('CREATED', 'AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED', 'TIMEOUT_UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Payment registry holding provider-confirmed state and the Aegis-X canonical state.';

CREATE INDEX idx_pi_payments_workspace_created ON pi_payments(workspace_id, created_at);
CREATE INDEX idx_pi_payments_order ON pi_payments(order_id);
CREATE INDEX idx_pi_payments_state ON pi_payments(state);

CREATE TABLE pi_refunds (
    id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_refund_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
    amount_minor       BIGINT NOT NULL,
    currency           VARCHAR(3) NOT NULL,
    provider_state     VARCHAR(30) NOT NULL,
    state              VARCHAR(30) NOT NULL,
    simulated          BIT(1) NOT NULL DEFAULT b'0',
    last_observed_at   DATETIME(6),
    failure_reason     VARCHAR(255),
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_refunds_provider_refund UNIQUE (workspace_id, provider_refund_id),
    CONSTRAINT fk_pi_refunds_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_refunds_payment FOREIGN KEY (payment_id) REFERENCES pi_payments(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_pi_refunds_state CHECK (state IN ('PENDING', 'PROCESSED', 'FAILED')),
    CONSTRAINT chk_pi_refunds_provider_state CHECK (provider_state IN ('PENDING', 'PROCESSED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_pi_refunds_payment ON pi_refunds(payment_id);

CREATE TABLE pi_transfers (
    id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    order_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_transfer_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
    linked_account_id    VARCHAR(100),
    amount_minor         BIGINT NOT NULL,
    currency             VARCHAR(3) NOT NULL,
    provider_state       VARCHAR(30) NOT NULL,
    state                VARCHAR(30) NOT NULL,
    simulated            BIT(1) NOT NULL DEFAULT b'0',
    last_observed_at     DATETIME(6),
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_transfers_provider_transfer UNIQUE (workspace_id, provider_transfer_id),
    CONSTRAINT fk_pi_transfers_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_transfers_payment FOREIGN KEY (payment_id) REFERENCES pi_payments(id) ON DELETE SET NULL,
    CONSTRAINT fk_pi_transfers_order FOREIGN KEY (order_id) REFERENCES pi_orders(id) ON DELETE SET NULL,
    CONSTRAINT chk_pi_transfers_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_pi_transfers_state CHECK (state IN ('CREATED', 'PENDING', 'PROCESSED', 'FAILED', 'REVERSED')),
    CONSTRAINT chk_pi_transfers_provider_state CHECK (provider_state IN
        ('CREATED', 'PENDING', 'PROCESSED', 'FAILED', 'REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_pi_transfers_payment ON pi_transfers(payment_id);

-- -----------------------------------------------------------------------------
-- Webhook ingestion (V21)
-- uq_pi_webhook_events_provider_event is THE idempotency guard: one row per unique
-- provider event, enforced by the database rather than by an in-memory set.
-- -----------------------------------------------------------------------------
CREATE TABLE pi_webhook_events (
    id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider             VARCHAR(30) NOT NULL,
    provider_event_id    VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    event_type           VARCHAR(80) NOT NULL,
    signature_verified   BIT(1) NOT NULL DEFAULT b'0',
    processing_state     VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    duplicate_of         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_resource_id VARCHAR(100),
    provider_created_at  DATETIME(6),
    received_at          DATETIME(6) NOT NULL,
    processed_at         DATETIME(6),
    payload              TEXT,
    payload_purged       BIT(1) NOT NULL DEFAULT b'0',
    error_message        VARCHAR(500),
    simulated            BIT(1) NOT NULL DEFAULT b'0',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_webhook_events_provider_event UNIQUE (provider, provider_event_id),
    CONSTRAINT fk_pi_webhook_events_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_webhook_events_state CHECK (processing_state IN
        ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'FAILED', 'IGNORED_TERMINAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Deduplicated provider events. The unique (provider, provider_event_id) constraint makes repeated delivery harmless.';

CREATE INDEX idx_pi_webhook_events_workspace_received ON pi_webhook_events(workspace_id, received_at);
CREATE INDEX idx_pi_webhook_events_resource ON pi_webhook_events(provider_resource_id);

CREATE TABLE pi_webhook_deliveries (
    id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider           VARCHAR(30) NOT NULL,
    provider_event_id  VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    event_type         VARCHAR(80) NOT NULL,
    event_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    first_delivery     BIT(1) NOT NULL DEFAULT b'1',
    signature_verified BIT(1) NOT NULL DEFAULT b'0',
    received_at        DATETIME(6) NOT NULL,
    provider_created_at DATETIME(6),
    body_sha256        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    simulated          BIT(1) NOT NULL DEFAULT b'0',
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_webhook_deliveries_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_webhook_deliveries_event FOREIGN KEY (event_id) REFERENCES pi_webhook_events(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only log of every received delivery, including repeats.';

CREATE INDEX idx_pi_webhook_deliveries_workspace_received ON pi_webhook_deliveries(workspace_id, received_at);
CREATE INDEX idx_pi_webhook_deliveries_event ON pi_webhook_deliveries(event_id);

-- -----------------------------------------------------------------------------
-- Observations, transitions, runs, findings (V22, V26 widens the finding taxonomy)
-- idx_pi_transitions_conflict was a partial index (WHERE conflict); it is plain here.
-- -----------------------------------------------------------------------------
CREATE TABLE pi_provider_observations (
    id                   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payment_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    transfer_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resource_type        VARCHAR(20) NOT NULL,
    provider_resource_id VARCHAR(100) NOT NULL,
    source               VARCHAR(30) NOT NULL,
    evidence_rank        INTEGER NOT NULL,
    observed_state       VARCHAR(30) NOT NULL,
    observed_amount_minor BIGINT,
    observed_currency    VARCHAR(3),
    payload              TEXT,
    observed_at          DATETIME(6) NOT NULL,
    simulated            BIT(1) NOT NULL DEFAULT b'0',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_observations_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_observations_resource CHECK (resource_type IN ('ORDER', 'PAYMENT', 'REFUND', 'TRANSFER')),
    CONSTRAINT chk_pi_observations_source CHECK (source IN
        ('PROVIDER_API_CONFIRMED', 'PROVIDER_WEBHOOK', 'INTERNAL_RECORD', 'INFERRED', 'SIMULATED')),
    CONSTRAINT chk_pi_observations_rank CHECK (evidence_rank BETWEEN 0 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only ranked observations. Provider-confirmed API state outranks a webhook observation, which outranks an internal record.';

CREATE INDEX idx_pi_observations_workspace ON pi_provider_observations(workspace_id, observed_at);
CREATE INDEX idx_pi_observations_order ON pi_provider_observations(order_id);
CREATE INDEX idx_pi_observations_payment ON pi_provider_observations(payment_id);
CREATE INDEX idx_pi_observations_resource_id ON pi_provider_observations(provider_resource_id);

CREATE TABLE pi_state_transitions (
    id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type        VARCHAR(20) NOT NULL,
    entity_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_state         VARCHAR(30),
    to_state           VARCHAR(30) NOT NULL,
    evidence_source    VARCHAR(30) NOT NULL,
    evidence_rank      INTEGER NOT NULL,
    provider_confirmed BIT(1) NOT NULL DEFAULT b'0',
    actor              VARCHAR(60) NOT NULL,
    reason             VARCHAR(500),
    conflict           BIT(1) NOT NULL DEFAULT b'0',
    occurred_at        DATETIME(6) NOT NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_transitions_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_transitions_entity CHECK (entity_type IN ('ORDER', 'PAYMENT', 'REFUND', 'TRANSFER')),
    CONSTRAINT chk_pi_transitions_source CHECK (evidence_source IN
        ('PROVIDER_API_CONFIRMED', 'PROVIDER_WEBHOOK', 'INTERNAL_RECORD', 'INFERRED', 'SIMULATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Canonical transition history. Rows with conflict = 1 were observed but deliberately not applied.';

CREATE INDEX idx_pi_transitions_entity ON pi_state_transitions(entity_type, entity_id, occurred_at);
CREATE INDEX idx_pi_transitions_workspace ON pi_state_transitions(workspace_id, occurred_at);
CREATE INDEX idx_pi_transitions_conflict ON pi_state_transitions(workspace_id);

CREATE TABLE pi_reconciliation_runs (
    id                     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trigger_type           VARCHAR(30) NOT NULL,
    strategy               VARCHAR(40) NOT NULL,
    started_at             DATETIME(6) NOT NULL,
    completed_at           DATETIME(6),
    orders_checked         INTEGER NOT NULL DEFAULT 0,
    payments_checked       INTEGER NOT NULL DEFAULT 0,
    refunds_checked        INTEGER NOT NULL DEFAULT 0,
    transfers_checked      INTEGER NOT NULL DEFAULT 0,
    findings_count         INTEGER NOT NULL DEFAULT 0,
    new_exceptions         INTEGER NOT NULL DEFAULT 0,
    reopened_exceptions    INTEGER NOT NULL DEFAULT 0,
    refreshed_exceptions   INTEGER NOT NULL DEFAULT 0,
    provider_reachable     BIT(1) NOT NULL DEFAULT b'1',
    notes                  VARCHAR(1000),
    correlation_id         VARCHAR(64),
    triggered_by           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_documents_checked INTEGER NOT NULL DEFAULT 0,
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_runs_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_runs_trigger CHECK (trigger_type IN
        ('MANUAL', 'WEBHOOK', 'SCHEDULED', 'BENCHMARK', 'EVIDENCE_ASSESSMENT')),
    CONSTRAINT chk_pi_runs_strategy CHECK (strategy IN
        ('BASELINE_1_DIRECT_COMPARISON', 'BASELINE_2_RULES_ENGINE', 'PROPOSED_EVIDENCE_BASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_pi_runs_workspace ON pi_reconciliation_runs(workspace_id, started_at);

CREATE TABLE pi_reconciliation_findings (
    id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payment_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    transfer_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    finding_type        VARCHAR(50) NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    certainty           VARCHAR(20) NOT NULL,
    summary             VARCHAR(500) NOT NULL,
    evidence_json       TEXT,
    missing_evidence_json TEXT,
    exception_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_findings_run FOREIGN KEY (run_id) REFERENCES pi_reconciliation_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_findings_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_findings_order FOREIGN KEY (order_id) REFERENCES pi_orders(id) ON DELETE SET NULL,
    CONSTRAINT fk_pi_findings_payment FOREIGN KEY (payment_id) REFERENCES pi_payments(id) ON DELETE SET NULL,
    CONSTRAINT chk_pi_findings_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_pi_findings_certainty CHECK (certainty IN ('CERTAIN', 'LIKELY', 'UNCERTAIN')),
    -- Final (V26-widened) taxonomy, including the evidence findings.
    CONSTRAINT chk_pi_findings_type CHECK (finding_type IN (
        'INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT',
        'PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER',
        'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH',
        'STATUS_MISMATCH',
        'DUPLICATE_EVENT_DELIVERY',
        'STALE_PAYMENT',
        'REFUND_DISCREPANCY',
        'TRANSFER_DISCREPANCY',
        'CONFLICTING_OBSERVATIONS',
        'MISSING_REQUIRED_EVIDENCE',
        'EVIDENCE_AMOUNT_MISMATCH',
        'EVIDENCE_CURRENCY_MISMATCH',
        'EVIDENCE_REFERENCE_MISMATCH',
        'DUPLICATE_EVIDENCE_DOCUMENT',
        'EVIDENCE_EXTRACTION_UNCERTAIN',
        'EVIDENCE_UNREADABLE',
        'EVIDENCE_ORPHANED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_pi_findings_run ON pi_reconciliation_findings(run_id);
CREATE INDEX idx_pi_findings_workspace_created ON pi_reconciliation_findings(workspace_id, created_at);
CREATE INDEX idx_pi_findings_exception ON pi_reconciliation_findings(exception_id);

-- -----------------------------------------------------------------------------
-- Cases, recommendations, review actions, integrity audit (V23, V27)
-- uq_pi_exceptions_dedupe is what makes repeated runs refresh a case instead of
-- duplicating it. The finding-type CHECK is the FINAL V27-widened form.
-- -----------------------------------------------------------------------------
CREATE TABLE pi_exceptions (
    id                       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dedupe_key               VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    finding_type             VARCHAR(50) NOT NULL,
    severity                 VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    certainty                VARCHAR(20) NOT NULL,
    order_id                 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payment_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    transfer_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_resource_ref    VARCHAR(120),
    title                    VARCHAR(200) NOT NULL,
    diagnosis                VARCHAR(1000) NOT NULL,
    supporting_evidence_json TEXT,
    missing_evidence_json    TEXT,
    recommended_action       VARCHAR(500),
    first_detected_run_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_seen_run_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    detection_count          INTEGER NOT NULL DEFAULT 1,
    assigned_to              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_summary       VARCHAR(1000),
    resolution_evidence_json TEXT,
    resolved_at              DATETIME(6),
    provider_unreachable     BIT(1) NOT NULL DEFAULT b'0',
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_exceptions_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT uq_pi_exceptions_dedupe UNIQUE (workspace_id, dedupe_key),
    CONSTRAINT chk_pi_exceptions_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT chk_pi_exceptions_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_pi_exceptions_certainty CHECK (certainty IN ('CERTAIN', 'LIKELY', 'UNCERTAIN')),
    CONSTRAINT chk_pi_exceptions_type CHECK (finding_type IN (
        'INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT',
        'PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER',
        'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH',
        'STATUS_MISMATCH',
        'DUPLICATE_EVENT_DELIVERY',
        'STALE_PAYMENT',
        'REFUND_DISCREPANCY',
        'TRANSFER_DISCREPANCY',
        'CONFLICTING_OBSERVATIONS',
        'MISSING_REQUIRED_EVIDENCE',
        'EVIDENCE_AMOUNT_MISMATCH',
        'EVIDENCE_CURRENCY_MISMATCH',
        'EVIDENCE_REFERENCE_MISMATCH',
        'DUPLICATE_EVIDENCE_DOCUMENT',
        'EVIDENCE_EXTRACTION_UNCERTAIN',
        'EVIDENCE_UNREADABLE',
        'EVIDENCE_ORPHANED')),
    CONSTRAINT chk_pi_exceptions_detection_count CHECK (detection_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='One case per (workspace, dedupe_key). dedupe_key is <FINDING_TYPE>|<entity reference>.';

CREATE INDEX idx_pi_exceptions_workspace_status ON pi_exceptions(workspace_id, status);
CREATE INDEX idx_pi_exceptions_severity ON pi_exceptions(workspace_id, severity);
CREATE INDEX idx_pi_exceptions_payment ON pi_exceptions(payment_id);

CREATE TABLE pi_recovery_recommendations (
    id                     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exception_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recommendation_type    VARCHAR(40) NOT NULL,
    category               VARCHAR(30) NOT NULL,
    status                 VARCHAR(30) NOT NULL,
    rationale              VARCHAR(500) NOT NULL,
    risk_note              VARCHAR(500),
    requires_human_approval BIT(1) NOT NULL DEFAULT b'1',
    executed_at            DATETIME(6),
    execution_outcome      VARCHAR(500),
    executed_by            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_recommendations_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_recommendations_exception FOREIGN KEY (exception_id) REFERENCES pi_exceptions(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_recommendations_category CHECK (category IN ('READ_ONLY', 'HUMAN_APPROVAL_REQUIRED', 'FINANCIAL')),
    CONSTRAINT chk_pi_recommendations_status CHECK (status IN
        ('PROPOSED', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED', 'EXECUTION_DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_pi_recommendations_exception ON pi_recovery_recommendations(exception_id);
CREATE INDEX idx_pi_recommendations_workspace_status ON pi_recovery_recommendations(workspace_id, status);

CREATE TABLE pi_review_actions (
    id                        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    exception_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recommendation_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action_type               VARCHAR(40) NOT NULL,
    actor_id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_role                VARCHAR(40) NOT NULL,
    rationale                 VARCHAR(1000) NOT NULL,
    evidence_json             TEXT,
    resulting_exception_status VARCHAR(20),
    created_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_review_actions_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_review_actions_exception FOREIGN KEY (exception_id) REFERENCES pi_exceptions(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_review_actions_type CHECK (action_type IN (
        'OPEN_CASE', 'ADD_NOTE', 'APPROVE_RECOMMENDATION', 'REJECT_RECOMMENDATION',
        'EXECUTE_READ_ONLY_RECOMMENDATION', 'RECORD_RESOLUTION', 'DISMISS', 'AUTO_REOPEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_pi_review_actions_exception ON pi_review_actions(exception_id, created_at);
CREATE INDEX idx_pi_review_actions_workspace ON pi_review_actions(workspace_id, created_at);

CREATE TABLE pi_audit_events (
    id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    event_type     VARCHAR(60) NOT NULL,
    category       VARCHAR(30) NOT NULL,
    actor_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_label    VARCHAR(120) NOT NULL,
    order_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payment_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    transfer_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    exception_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    run_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64),
    detail_json    TEXT,
    simulated      BIT(1) NOT NULL DEFAULT b'0',
    occurred_at    DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_audit_workspace FOREIGN KEY (workspace_id) REFERENCES pi_workspaces(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only audit trail for the integrity module. Anti-update/delete is enforced by the application, NOT by a hash chain or database privilege.';

CREATE INDEX idx_pi_audit_workspace_occurred ON pi_audit_events(workspace_id, occurred_at);
CREATE INDEX idx_pi_audit_event_type ON pi_audit_events(event_type);
CREATE INDEX idx_pi_audit_exception ON pi_audit_events(exception_id);

-- -----------------------------------------------------------------------------
-- Evidence intelligence (V26)
--
-- THE ONE NON-PORTABLE INVARIANT.
-- PostgreSQL enforces "at most one ACTIVE document per (order, type)" with
--     CREATE UNIQUE INDEX ... ON pi_evidence_documents(order_id, document_type)
--         WHERE superseded_by_document_id IS NULL;
-- MySQL has no partial indexes, and a plain unique index cannot express it because
-- the predicate column is NULL on exactly the rows that must be deduplicated, and
-- MySQL treats NULLs as non-colliding.
--
-- The generated column moves the predicate into the key:
--   * active row          -> active_document_type = document_type  => uniqueness applies
--   * superseded row      -> active_document_type = NULL           => ignored by the index,
--                            so any number of superseded documents may coexist
-- order_id is NOT NULL, so no key is skipped for the wrong reason.
-- Verified by MySqlEvidenceUniquenessTest, in both directions.
--
-- WHY VIRTUAL AND NOT STORED (found empirically, not from documentation):
--   superseded_by_document_id is the base column of this generated column, and it also
--   carries a self-referencing foreign key with ON DELETE SET NULL. MySQL rejects that
--   combination for a STORED generated column with error 1215 ("Cannot add foreign key
--   constraint"); the restriction does not apply to a VIRTUAL generated column, which can
--   still be the leading part of a secondary unique index. Measured: with STORED the table
--   could not be created at all; with VIRTUAL it is created with all three foreign keys and
--   the unique index, and a duplicate active document is rejected with error 1062.
-- -----------------------------------------------------------------------------
CREATE TABLE pi_evidence_documents (
    id                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    document_type         VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    origin                VARCHAR(30) NOT NULL DEFAULT 'MERCHANT_UPLOAD',
    original_filename     VARCHAR(255) NOT NULL,
    stored_filename       VARCHAR(255) NOT NULL,
    mime_type             VARCHAR(100) NOT NULL,
    file_size             BIGINT NOT NULL,
    file_hash             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_path          VARCHAR(512) NOT NULL,
    uploaded_by           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_label           VARCHAR(120) NOT NULL,
    processing_status      VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    processing_error       VARCHAR(500),
    extracted_invoice_number  VARCHAR(120),
    extracted_invoice_date    DATE,
    extracted_amount_minor    BIGINT,
    extracted_currency        VARCHAR(3),
    extracted_order_reference VARCHAR(120),
    extracted_payment_reference VARCHAR(120),
    fields_confirmed      BIT(1) NOT NULL DEFAULT b'0',
    confirmed_by          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    confirmed_at          DATETIME(6),
    supersedes_document_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    superseded_by_document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    simulated             BIT(1) NOT NULL DEFAULT b'0',
    -- NOT mapped by EvidenceDocument. Hibernate validation ignores extra columns.
    active_document_type  VARCHAR(50) COLLATE utf8mb4_0900_as_cs
        GENERATED ALWAYS AS (IF(superseded_by_document_id IS NULL, document_type, NULL)) VIRTUAL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_evidence_doc_workspace FOREIGN KEY (workspace_id)
        REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_evidence_doc_order FOREIGN KEY (order_id)
        REFERENCES pi_orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_evidence_doc_supersedes FOREIGN KEY (supersedes_document_id)
        REFERENCES pi_evidence_documents(id) ON DELETE SET NULL,
    CONSTRAINT fk_pi_evidence_doc_superseded_by FOREIGN KEY (superseded_by_document_id)
        REFERENCES pi_evidence_documents(id) ON DELETE SET NULL,
    CONSTRAINT chk_pi_evidence_doc_origin CHECK (origin IN
        ('MERCHANT_UPLOAD', 'PROVIDER_ATTACHMENT', 'SYSTEM_GENERATED')),
    CONSTRAINT chk_pi_evidence_doc_processing CHECK (processing_status IN
        ('RECEIVED', 'PROCESSING', 'EXTRACTED', 'FAILED')),
    CONSTRAINT chk_pi_evidence_doc_size CHECK (file_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Supporting documents attached to a payment integrity order. Content-hash deduplicated, superseded rather than deleted.';

CREATE INDEX idx_pi_evidence_doc_order ON pi_evidence_documents(order_id, created_at);
CREATE INDEX idx_pi_evidence_doc_workspace ON pi_evidence_documents(workspace_id, created_at);
CREATE INDEX idx_pi_evidence_doc_hash ON pi_evidence_documents(workspace_id, file_hash);
CREATE INDEX idx_pi_evidence_doc_type ON pi_evidence_documents(order_id, document_type);
-- Replaces the PostgreSQL partial unique index. See the block comment above.
CREATE UNIQUE INDEX uq_pi_evidence_doc_active_type
    ON pi_evidence_documents(order_id, active_document_type);

CREATE TABLE pi_evidence_extractions (
    id                    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    extraction_method     VARCHAR(30) NOT NULL,
    raw_text              TEXT,
    extracted_data        TEXT,
    uncertain_fields      TEXT,
    absent_fields         TEXT,
    overall_confidence    DOUBLE,
    processing_duration_ms BIGINT,
    ocr_language          VARCHAR(10),
    failure_reason        VARCHAR(500),
    actor_label           VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    human_correction      BIT(1) NOT NULL DEFAULT b'0',
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pi_evidence_extraction_workspace FOREIGN KEY (workspace_id)
        REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_evidence_extraction_document FOREIGN KEY (document_id)
        REFERENCES pi_evidence_documents(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_evidence_extraction_method CHECK (extraction_method IN
        ('TIKA', 'TESSERACT', 'MANUAL_CORRECTION', 'NOT_ATTEMPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only extraction history, including human corrections. A correction never edits the machine reading.';

CREATE INDEX idx_pi_evidence_extraction_document ON pi_evidence_extractions(document_id, created_at);
CREATE INDEX idx_pi_evidence_extraction_workspace ON pi_evidence_extractions(workspace_id, created_at);

CREATE TABLE pi_evidence_readiness (
    id                        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                    VARCHAR(30) NOT NULL,
    reason                    VARCHAR(500) NOT NULL,
    required_document_types   TEXT,
    missing_document_types    TEXT,
    uncertain_document_types  TEXT,
    blocking_findings         INTEGER NOT NULL DEFAULT 0,
    documents_present         INTEGER NOT NULL DEFAULT 0,
    documents_expected        INTEGER NOT NULL DEFAULT 0,
    assessment_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_assessed_at          DATETIME(6) NOT NULL,
    assessed_by               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_pi_evidence_readiness_order UNIQUE (workspace_id, order_id),
    CONSTRAINT fk_pi_evidence_readiness_workspace FOREIGN KEY (workspace_id)
        REFERENCES pi_workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_evidence_readiness_order FOREIGN KEY (order_id)
        REFERENCES pi_orders(id) ON DELETE CASCADE,
    CONSTRAINT chk_pi_evidence_readiness_status CHECK (status IN
        ('PENDING_EVIDENCE', 'PROCESSING', 'READY_FOR_REVIEW', 'EVIDENCE_COMPLETE',
         'ACTION_REQUIRED', 'PROCESSING_FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Evidence completeness and mutual consistency for one order. A separate dimension from payment state.';

CREATE INDEX idx_pi_evidence_readiness_workspace ON pi_evidence_readiness(workspace_id, status);
CREATE INDEX idx_pi_evidence_readiness_order ON pi_evidence_readiness(order_id);
