-- =============================================================================
-- Aegis-X XAMPP Demo Seed Data
-- Target schema : aegisx_xampp  (MariaDB via XAMPP, localhost:3306)
-- Profile       : xampp  (application-xampp.yml, ddl-auto=update, Flyway disabled)
-- UUID strategy : CHAR(36) preferred_uuid_jdbc_type=CHAR
--
-- Idempotent: safe to re-run (INSERT IGNORE throughout).
-- Passwords   : BCrypt cost-12
--   admin@aegisx.io      → Admin@1234
--   compliance@aegisx.io → Officer@1234
--   settlement@aegisx.io → Settle@1234
--   merchant@acmetrade.com → Demo@1234
-- =============================================================================

USE aegisx_xampp;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. USERS
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO users (id, created_at, updated_at, email, full_name, password_hash, phone, role) VALUES
-- Platform Admin
('00000000-0000-0000-0000-000000000001',
 '2026-10-01 09:00:00.000000', '2026-10-01 09:00:00.000000',
 'admin@aegisx.io', 'Platform Admin',
 '$2a$12$Gqxm1yrgLZoofCAjiRmigePp4HYQhxXQVdcJm5m0.Sa5/J196LQ0a',
 '+919800000001', 'ADMIN'),

-- Compliance Officer
('00000000-0000-0000-0000-000000000002',
 '2026-10-01 09:05:00.000000', '2026-10-01 09:05:00.000000',
 'compliance@aegisx.io', 'Priya Sharma',
 '$2a$12$LzAwBZIPkbFj7SvlF3cJj.RLM3Wpug.rpsup8VI/i4dMM.bKYy4Ny',
 '+919800000002', 'COMPLIANCE_OFFICER'),

-- Settlement Operator
('00000000-0000-0000-0000-000000000003',
 '2026-10-01 09:10:00.000000', '2026-10-01 09:10:00.000000',
 'settlement@aegisx.io', 'Rajan Mehta',
 '$2a$12$c6ED8GEP8PUWVW/dWf1TOuVe8lKID50sFjhG9AP3AHeHeRW1LWsiK',
 '+919800000003', 'SETTLEMENT_OPERATOR'),

-- Merchant
('00000000-0000-0000-0000-000000000004',
 '2026-10-02 10:00:00.000000', '2026-10-02 10:00:00.000000',
 'merchant@acmetrade.com', 'Acme Trade Pvt Ltd',
 '$2a$12$..FZWBo20GJx.KV8BRRn2eNMF4jABi.ktZAcnsOkLn9fiKJCoUS1a',
 '+919900000010', 'MERCHANT');

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. PI WORKSPACE  (Integrity workspace for the merchant)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO pi_workspaces (id, created_at, updated_at, default_currency, environment, name,
  provider_configured, provider_key_id_masked, provider_mode, staleness_threshold_seconds, transfer_capability) VALUES
('ws-00000000-0000-0000-0000-000000000001',
 '2026-10-02 10:30:00.000000', '2026-11-01 08:00:00.000000',
 'USD', 'TEST', 'Acme Trade - Export Payments',
 1, 'rzp_test_xxxx****1234', 'RAZORPAY_TEST', 300, 'SIMULATED');

-- Workspace member (merchant as OWNER)
INSERT IGNORE INTO workspace_members (id, created_at, updated_at, role, user_id, workspace_id) VALUES
('wm-00000000-0000-0000-0000-000000000001',
 '2026-10-02 10:30:00.000000', '2026-10-02 10:30:00.000000',
 'OWNER',
 '00000000-0000-0000-0000-000000000004',
 'ws-00000000-0000-0000-0000-000000000001');

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. TRANSACTIONS
--   TX-001 : APPROVED   – export of machine parts to Germany
--   TX-002 : SETTLED    – textile export to UAE, fully settled
--   TX-003 : COMPLIANCE_HOLD – high-value pharma, flagged by policy engine
--   TX-004 : EVIDENCE_PENDING – auto parts, waiting for docs
--   TX-005 : REJECTED    – suspicious destination, rejected by compliance officer
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO transactions
  (id, created_at, updated_at, amount, beneficiary_name, currency, is_terminal,
   purpose_code, razorpay_order_id, reference_number, status, version, merchant_id) VALUES

-- TX-001 APPROVED
('tx-00000000-0000-0000-0000-000000000001',
 '2026-11-10 08:30:00.000000', '2026-11-10 14:22:00.000000',
 45000.00, 'Mueller Engineering GmbH', 'USD', 0,
 'P0101', 'order_NxKQ7mZA3bRtP1', 'REF-2026-DE-0045',
 'APPROVED', 5,
 '00000000-0000-0000-0000-000000000004'),

-- TX-002 SETTLED
('tx-00000000-0000-0000-0000-000000000002',
 '2026-11-05 11:00:00.000000', '2026-11-07 09:15:00.000000',
 12750.00, 'Gulf Textiles LLC', 'USD', 1,
 'P0102', 'order_MwJP5nYB2aQsU8', 'REF-2026-AE-0031',
 'SETTLED', 8,
 '00000000-0000-0000-0000-000000000004'),

-- TX-003 COMPLIANCE_HOLD
('tx-00000000-0000-0000-0000-000000000003',
 '2026-11-18 15:45:00.000000', '2026-11-18 16:10:00.000000',
 182000.00, 'Pharma Global FZE', 'USD', 0,
 'P0101', NULL, 'REF-2026-AE-0062',
 'COMPLIANCE_HOLD', 3,
 '00000000-0000-0000-0000-000000000004'),

-- TX-004 EVIDENCE_PENDING
('tx-00000000-0000-0000-0000-000000000004',
 '2026-11-25 09:00:00.000000', '2026-11-25 09:05:00.000000',
 8900.00, 'AutoParts Distributors Inc', 'USD', 0,
 'P0101', NULL, 'REF-2026-US-0078',
 'EVIDENCE_PENDING', 2,
 '00000000-0000-0000-0000-000000000004'),

-- TX-005 REJECTED
('tx-00000000-0000-0000-0000-000000000005',
 '2026-11-12 13:00:00.000000', '2026-11-13 11:30:00.000000',
 97500.00, 'Unknown Commodities Ltd', 'USD', 1,
 'P0102', NULL, 'REF-2026-XX-0051',
 'REJECTED', 7,
 '00000000-0000-0000-0000-000000000004');

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. REQUIRED DOCUMENTS  (what must be uploaded per transaction)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO required_documents (id, created_at, updated_at, document_type, fulfilled, document_id, transaction_id) VALUES
-- TX-001 APPROVED – all docs fulfilled
('rd-tx1-01','2026-11-10 08:35:00.000000','2026-11-10 10:00:00.000000','COMMERCIAL_INVOICE',  1,'doc-tx1-inv',  'tx-00000000-0000-0000-0000-000000000001'),
('rd-tx1-02','2026-11-10 08:35:00.000000','2026-11-10 10:05:00.000000','SHIPPING_BILL',       1,'doc-tx1-shp',  'tx-00000000-0000-0000-0000-000000000001'),
('rd-tx1-03','2026-11-10 08:35:00.000000','2026-11-10 10:10:00.000000','PACKING_LIST',        1,'doc-tx1-pck',  'tx-00000000-0000-0000-0000-000000000001'),
('rd-tx1-04','2026-11-10 08:35:00.000000','2026-11-10 10:15:00.000000','CERTIFICATE_OF_ORIGIN',1,'doc-tx1-coo', 'tx-00000000-0000-0000-0000-000000000001'),
-- TX-002 SETTLED – docs fulfilled
('rd-tx2-01','2026-11-05 11:05:00.000000','2026-11-05 14:00:00.000000','COMMERCIAL_INVOICE',  1,'doc-tx2-inv',  'tx-00000000-0000-0000-0000-000000000002'),
('rd-tx2-02','2026-11-05 11:05:00.000000','2026-11-05 14:10:00.000000','SHIPPING_BILL',       1,'doc-tx2-shp',  'tx-00000000-0000-0000-0000-000000000002'),
('rd-tx2-03','2026-11-05 11:05:00.000000','2026-11-05 14:15:00.000000','PACKING_LIST',        1,'doc-tx2-pck',  'tx-00000000-0000-0000-0000-000000000002'),
-- TX-003 COMPLIANCE_HOLD – docs uploaded but held by policy
('rd-tx3-01','2026-11-18 15:50:00.000000','2026-11-18 16:00:00.000000','COMMERCIAL_INVOICE',  1,'doc-tx3-inv',  'tx-00000000-0000-0000-0000-000000000003'),
('rd-tx3-02','2026-11-18 15:50:00.000000','2026-11-18 16:01:00.000000','SHIPPING_BILL',       1,'doc-tx3-shp',  'tx-00000000-0000-0000-0000-000000000003'),
('rd-tx3-03','2026-11-18 15:50:00.000000','2026-11-18 16:02:00.000000','EXPORT_DECLARATION',  1,'doc-tx3-exp',  'tx-00000000-0000-0000-0000-000000000003'),
-- TX-004 EVIDENCE_PENDING – not yet uploaded
('rd-tx4-01','2026-11-25 09:05:00.000000','2026-11-25 09:05:00.000000','COMMERCIAL_INVOICE',  0, NULL, 'tx-00000000-0000-0000-0000-000000000004'),
('rd-tx4-02','2026-11-25 09:05:00.000000','2026-11-25 09:05:00.000000','SHIPPING_BILL',       0, NULL, 'tx-00000000-0000-0000-0000-000000000004'),
-- TX-005 REJECTED – docs were uploaded
('rd-tx5-01','2026-11-12 13:05:00.000000','2026-11-12 15:00:00.000000','COMMERCIAL_INVOICE',  1,'doc-tx5-inv',  'tx-00000000-0000-0000-0000-000000000005'),
('rd-tx5-02','2026-11-12 13:05:00.000000','2026-11-12 15:05:00.000000','EXPORT_DECLARATION',  1,'doc-tx5-exp',  'tx-00000000-0000-0000-0000-000000000005');

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. DOCUMENTS  (uploaded files metadata)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO documents
  (id, created_at, updated_at, document_status, document_type, file_hash, file_size,
   mime_type, original_filename, storage_path, stored_filename, version, transaction_id, uploaded_by) VALUES
-- TX-001
('doc-tx1-inv','2026-11-10 10:00:00.000000','2026-11-10 10:30:00.000000','OCR_COMPLETED','COMMERCIAL_INVOICE',
 'a1b2c3d4e5f67890abcdef1234567890a1b2c3d4e5f67890abcdef1234567890',102400,
 'application/pdf','commercial_invoice_mueller.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000001/','commercial_invoice_mueller_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004'),
('doc-tx1-shp','2026-11-10 10:05:00.000000','2026-11-10 10:35:00.000000','OCR_COMPLETED','SHIPPING_BILL',
 'b2c3d4e5f67890abcdef1234567890a1b2c3d4e5f67890abcdef1234567891a1',87040,
 'application/pdf','shipping_bill_DE045.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000001/','shipping_bill_DE045_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004'),
('doc-tx1-pck','2026-11-10 10:10:00.000000','2026-11-10 10:40:00.000000','OCR_COMPLETED','PACKING_LIST',
 'c3d4e5f67890abcdef1234567890a1b2c3d4e5f67890abcdef1234567892a1b2',45056,
 'application/pdf','packing_list_DE045.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000001/','packing_list_DE045_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004'),
('doc-tx1-coo','2026-11-10 10:15:00.000000','2026-11-10 10:45:00.000000','OCR_COMPLETED','CERTIFICATE_OF_ORIGIN',
 'd4e5f67890abcdef1234567890a1b2c3d4e5f67890abcdef1234567893a1b2c3',61440,
 'application/pdf','certificate_of_origin_DE045.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000001/','coo_DE045_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004'),
-- TX-002
('doc-tx2-inv','2026-11-05 14:00:00.000000','2026-11-05 14:20:00.000000','OCR_COMPLETED','COMMERCIAL_INVOICE',
 'e5f67890abcdef1234567890a1b2c3d4e5f67890abcdef1234567894a1b2c3d4',93184,
 'application/pdf','invoice_gulf_textiles.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000002/','invoice_gulf_textiles_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004'),
('doc-tx2-shp','2026-11-05 14:10:00.000000','2026-11-05 14:25:00.000000','OCR_COMPLETED','SHIPPING_BILL',
 'f67890abcdef1234567890a1b2c3d4e5f67890abcdef1234567895a1b2c3d4e5',76800,
 'application/pdf','shipping_bill_AE031.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000002/','shipping_bill_AE031_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004'),
('doc-tx2-pck','2026-11-05 14:15:00.000000','2026-11-05 14:28:00.000000','OCR_COMPLETED','PACKING_LIST',
 '7890abcdef1234567890a1b2c3d4e5f67890abcdef1234567896a1b2c3d4e5f6',40960,
 'application/pdf','packing_list_AE031.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000002/','packing_list_AE031_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004'),
-- TX-003
('doc-tx3-inv','2026-11-18 16:00:00.000000','2026-11-18 16:20:00.000000','OCR_COMPLETED','COMMERCIAL_INVOICE',
 '890abcdef1234567890a1b2c3d4e5f67890abcdef1234567897a1b2c3d4e5f67',118784,
 'application/pdf','invoice_pharma_global.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000003/','invoice_pharma_global_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004'),
('doc-tx3-shp','2026-11-18 16:01:00.000000','2026-11-18 16:21:00.000000','OCR_COMPLETED','SHIPPING_BILL',
 '90abcdef1234567890a1b2c3d4e5f67890abcdef1234567898a1b2c3d4e5f678',84992,
 'application/pdf','shipping_bill_AE062.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000003/','shipping_bill_AE062_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004'),
('doc-tx3-exp','2026-11-18 16:02:00.000000','2026-11-18 16:22:00.000000','OCR_COMPLETED','EXPORT_DECLARATION',
 '0abcdef1234567890a1b2c3d4e5f67890abcdef1234567899a1b2c3d4e5f6789',71680,
 'application/pdf','export_declaration_AE062.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000003/','export_decl_AE062_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004'),
-- TX-005
('doc-tx5-inv','2026-11-12 15:00:00.000000','2026-11-12 15:30:00.000000','OCR_COMPLETED','COMMERCIAL_INVOICE',
 'abcdef1234567890a1b2c3d4e5f67890abcdef1234567900a1b2c3d4e5f67890',102400,
 'application/pdf','invoice_unknown_commodities.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000005/','invoice_unknown_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000004'),
('doc-tx5-exp','2026-11-12 15:05:00.000000','2026-11-12 15:35:00.000000','OCR_COMPLETED','EXPORT_DECLARATION',
 'bcdef1234567890a1b2c3d4e5f67890abcdef1234567901a1b2c3d4e5f678901',67584,
 'application/pdf','export_declaration_XX051.pdf',
 'uploads/tx-00000000-0000-0000-0000-000000000005/','export_decl_XX051_v1.pdf',1,
 'tx-00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000004');

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. EVIDENCE CHECKLISTS
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO evidence_checklists
  (id, created_at, updated_at, certificate_origin_required, certificate_origin_uploaded,
   evaluated_at, invoice_required, invoice_uploaded, is_satisfied,
   packing_list_required, packing_list_uploaded, shipping_bill_required, shipping_bill_uploaded, transaction_id) VALUES
-- TX-001 fully satisfied
('ec-tx1','2026-11-10 10:50:00.000000','2026-11-10 10:50:00.000000',1,1,'2026-11-10 10:50:00.000000',1,1,1,1,1,1,1,'tx-00000000-0000-0000-0000-000000000001'),
-- TX-002 satisfied
('ec-tx2','2026-11-05 14:30:00.000000','2026-11-05 14:30:00.000000',0,0,'2026-11-05 14:30:00.000000',1,1,1,1,1,1,1,'tx-00000000-0000-0000-0000-000000000002'),
-- TX-003 satisfied but held by policy
('ec-tx3','2026-11-18 16:25:00.000000','2026-11-18 16:25:00.000000',0,0,'2026-11-18 16:25:00.000000',1,1,1,0,0,1,1,'tx-00000000-0000-0000-0000-000000000003'),
-- TX-004 NOT satisfied (pending)
('ec-tx4','2026-11-25 09:05:00.000000','2026-11-25 09:05:00.000000',0,0,'2026-11-25 09:05:00.000000',1,0,0,0,0,1,0,'tx-00000000-0000-0000-0000-000000000004'),
-- TX-005 satisfied but then rejected
('ec-tx5','2026-11-12 15:40:00.000000','2026-11-12 15:40:00.000000',0,0,'2026-11-12 15:40:00.000000',1,1,1,0,0,0,0,'tx-00000000-0000-0000-0000-000000000005');

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. POLICY EVALUATIONS  (for TX-001 APPROVED and TX-003 HOLD and TX-005 REJECTED)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO policy_evaluations
  (id, created_at, updated_at, engine_version, evaluated_at, evaluation_json,
   failed_rules_count, final_decision, overall_score, passed_rules_count,
   processing_duration_ms, total_rules_count, verdict, ai_analysis_id, transaction_id) VALUES
-- TX-001 APPROVED
('pe-tx1','2026-11-10 11:00:00.000000','2026-11-10 11:00:00.000000','2.1.0','2026-11-10 11:00:00.000000',
 '{"rules":[{"id":"PR-01","name":"Amount Threshold","result":"PASS"},{"id":"PR-02","name":"Purpose Code Whitelist","result":"PASS"},{"id":"PR-03","name":"Beneficiary Verification","result":"PASS"},{"id":"PR-04","name":"Document Completeness","result":"PASS"},{"id":"PR-05","name":"FEMA Compliance","result":"PASS"}]}',
 0,'APPROVED',100.00,5,342,5,'APPROVED',NULL,'tx-00000000-0000-0000-0000-000000000001'),
-- TX-003 HOLD (high value + suspicious)
('pe-tx3','2026-11-18 16:30:00.000000','2026-11-18 16:30:00.000000','2.1.0','2026-11-18 16:30:00.000000',
 '{"rules":[{"id":"PR-01","name":"Amount Threshold","result":"FAIL","reason":"Amount USD 182,000 exceeds high-value threshold of USD 100,000"},{"id":"PR-02","name":"Purpose Code Whitelist","result":"PASS"},{"id":"PR-03","name":"Beneficiary Verification","result":"FAIL","reason":"Beneficiary in FATF monitored jurisdiction"},{"id":"PR-04","name":"Document Completeness","result":"PASS"},{"id":"PR-05","name":"FEMA Compliance","result":"PASS"}]}',
 2,'HOLD',58.00,3,489,5,'HOLD',NULL,'tx-00000000-0000-0000-0000-000000000003'),
-- TX-005 REJECTED (before manual review)
('pe-tx5','2026-11-12 15:50:00.000000','2026-11-12 15:50:00.000000','2.1.0','2026-11-12 15:50:00.000000',
 '{"rules":[{"id":"PR-01","name":"Amount Threshold","result":"FAIL","reason":"Amount USD 97,500 requires enhanced due diligence"},{"id":"PR-02","name":"Purpose Code Whitelist","result":"PASS"},{"id":"PR-03","name":"Beneficiary Verification","result":"FAIL","reason":"Unknown beneficiary entity, not registered"},{"id":"PR-04","name":"Document Completeness","result":"PASS"},{"id":"PR-05","name":"FEMA Compliance","result":"FAIL","reason":"Destination not in approved FEMA schedule"}]}',
 3,'HOLD',32.00,2,521,5,'HOLD',NULL,'tx-00000000-0000-0000-0000-000000000005'),
-- TX-002 APPROVED (settled)
('pe-tx2','2026-11-05 15:00:00.000000','2026-11-05 15:00:00.000000','2.1.0','2026-11-05 15:00:00.000000',
 '{"rules":[{"id":"PR-01","name":"Amount Threshold","result":"PASS"},{"id":"PR-02","name":"Purpose Code Whitelist","result":"PASS"},{"id":"PR-03","name":"Beneficiary Verification","result":"PASS"},{"id":"PR-04","name":"Document Completeness","result":"PASS"},{"id":"PR-05","name":"FEMA Compliance","result":"PASS"}]}',
 0,'APPROVED',100.00,5,298,5,'APPROVED',NULL,'tx-00000000-0000-0000-0000-000000000002');

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. POLICY VIOLATIONS  (for TX-003 HOLD and TX-005 REJECTED)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO policy_violations
  (id, created_at, updated_at, description, rule_context, rule_id, rule_name, severity, policy_evaluation_id) VALUES
-- TX-003 violations
('pv-tx3-01','2026-11-18 16:30:00.000000','2026-11-18 16:30:00.000000',
 'Transaction amount of USD 182,000 exceeds the high-value threshold of USD 100,000. Enhanced due diligence is mandatory.',
 '{"threshold":100000,"actual":182000,"currency":"USD"}',
 'PR-01','Amount Threshold','HIGH','pe-tx3'),
('pv-tx3-02','2026-11-18 16:30:00.000000','2026-11-18 16:30:00.000000',
 'Beneficiary entity "Pharma Global FZE" is located in a FATF-monitored jurisdiction requiring additional KYC.',
 '{"jurisdiction":"AE","fatfStatus":"MONITORED","beneficiary":"Pharma Global FZE"}',
 'PR-03','Beneficiary Verification','MEDIUM','pe-tx3'),
-- TX-005 violations
('pv-tx5-01','2026-11-12 15:50:00.000000','2026-11-12 15:50:00.000000',
 'Transaction amount USD 97,500 requires enhanced due diligence per RBI circular RBI/2023-24/61.',
 '{"threshold":50000,"actual":97500,"currency":"USD","circular":"RBI/2023-24/61"}',
 'PR-01','Amount Threshold','HIGH','pe-tx5'),
('pv-tx5-02','2026-11-12 15:50:00.000000','2026-11-12 15:50:00.000000',
 'Beneficiary "Unknown Commodities Ltd" could not be verified against any registered entity database.',
 '{"checkResult":"NOT_FOUND","databases":["MCA21","GLEIF","SWIFT_BIC"]}',
 'PR-03','Beneficiary Verification','CRITICAL','pe-tx5'),
('pv-tx5-03','2026-11-12 15:50:00.000000','2026-11-12 15:50:00.000000',
 'Destination country not listed in RBI FEMA Schedule I approved export destinations.',
 '{"destinationCountry":"XX","feaSchedule":"SCHEDULE_I","approved":false}',
 'PR-05','FEMA Compliance','CRITICAL','pe-tx5');

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. COMPLIANCE REVIEWS  (manual review decisions)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO compliance_reviews
  (id, created_at, updated_at, decision, justification, previous_state,
   review_context, reviewed_at, reviewer_id, transaction_id) VALUES
-- TX-005 REJECTED by compliance officer
('cr-tx5','2026-11-13 11:25:00.000000','2026-11-13 11:25:00.000000',
 'REJECTED',
 'Beneficiary entity "Unknown Commodities Ltd" cannot be verified in any regulatory database (MCA21, GLEIF, SWIFT). Destination country is not in RBI-approved FEMA Schedule I list. Transaction poses significant AML/KYC risk. REJECTION recommended as per FEMA 1999 §10(4) and RBI Master Direction on KYC 2016 §37.',
 'COMPLIANCE_HOLD',
 '{"policyViolations":["PR-01","PR-03","PR-05"],"riskScore":"CRITICAL","reviewDuration":"22h"}',
 '2026-11-13 11:25:00.000000',
 '00000000-0000-0000-0000-000000000002',
 'tx-00000000-0000-0000-0000-000000000005');

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. WEBHOOK EVENTS  (simulated Razorpay payment.captured events)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO webhook_events
  (id, created_at, event_id, event_type, payload, payment_id,
   processing_status, provider, provider_event_timestamp, signature_verified, transaction_reference) VALUES
-- TX-001 payment
('we-tx1-01','2026-11-10 09:15:00.000000',
 'evt_NxKQ8aBC1234def','payment.captured',
 '{"entity":"event","account_id":"acc_test","event":"payment.captured","contains":["payment"],"payload":{"payment":{"entity":{"id":"pay_NxKQ8aBC1234","entity":"payment","amount":4500000,"currency":"USD","status":"captured","order_id":"order_NxKQ7mZA3bRtP1","method":"netbanking","captured":true}}}}',
 'pay_NxKQ8aBC1234','PROCESSED','RAZORPAY','2026-11-10 09:14:55.000000',1,'REF-2026-DE-0045'),
-- TX-002 payment
('we-tx2-01','2026-11-05 11:45:00.000000',
 'evt_MwJP6aCD5678ghi','payment.captured',
 '{"entity":"event","account_id":"acc_test","event":"payment.captured","contains":["payment"],"payload":{"payment":{"entity":{"id":"pay_MwJP6aCD5678","entity":"payment","amount":1275000,"currency":"USD","status":"captured","order_id":"order_MwJP5nYB2aQsU8","method":"netbanking","captured":true}}}}',
 'pay_MwJP6aCD5678','PROCESSED','RAZORPAY','2026-11-05 11:44:52.000000',1,'REF-2026-AE-0031'),
-- TX-002 payout event
('we-tx2-02','2026-11-06 08:00:00.000000',
 'evt_MwJP7bDE6789jkl','payout.processed',
 '{"entity":"event","account_id":"acc_test","event":"payout.processed","contains":["payout"],"payload":{"payout":{"entity":{"id":"pout_AE031xyz","entity":"payout","amount":1272750,"currency":"USD","status":"processed","fund_account_id":"fa_gulf001"}}}}',
 NULL,'PROCESSED','RAZORPAY','2026-11-06 07:59:48.000000',1,'REF-2026-AE-0031');

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. SETTLEMENTS  (TX-002 fully settled)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO settlements
  (id, created_at, updated_at, completed_at, exchange_rate, expected_completion_at,
   failure_reason, initiated_at, initiated_by, max_retries, next_retry_at,
   payment_processor, processing_fees, processor_metadata, processor_transaction_id,
   reconciled_at, reconciliation_data, retry_count, settlement_amount, settlement_currency,
   settlement_reference, status, submitted_at, transaction_id) VALUES
('stl-tx2','2026-11-06 09:00:00.000000','2026-11-07 09:10:00.000000',
 '2026-11-07 09:10:00.000000',83.42,'2026-11-07 18:00:00.000000',
 NULL,'2026-11-06 09:00:00.000000','settlement@aegisx.io',3,NULL,
 'RAZORPAYX',2.25,
 '{"razorpayx_payout_id":"pout_AE031xyz","mode":"NEFT","bank_account":"ICICI_001","utr":"UTR20261106001234"}',
 'pout_AE031xyz',
 '2026-11-07 09:10:00.000000',
 '{"matched":true,"utr":"UTR20261106001234","settled_amount":12747.75}',
 0,12747.75,'USD','SETL-2026-AE-0031','RECONCILED',
 '2026-11-06 09:05:00.000000',
 'tx-00000000-0000-0000-0000-000000000002');

-- ─────────────────────────────────────────────────────────────────────────────
-- 12. PI ORDERS  (Integrity payment orders)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO pi_orders
  (id, created_at, updated_at, correlation_id, created_by, currency, description,
   expected_amount_minor, internal_reference, last_observed_at, provider_order_id,
   provider_state, simulated, state, workspace_id) VALUES
('pio-tx1','2026-11-10 09:00:00.000000','2026-11-10 09:16:00.000000',
 'corr-DE-0045','00000000-0000-0000-0000-000000000004','USD',
 'Export payment – Mueller Engineering GmbH – REF-2026-DE-0045',
 4500000,'INT-REF-DE-0045','2026-11-10 09:14:55.000000',
 'order_NxKQ7mZA3bRtP1','PAID',0,'PAID',
 'ws-00000000-0000-0000-0000-000000000001'),
('pio-tx2','2026-11-05 11:00:00.000000','2026-11-05 11:46:00.000000',
 'corr-AE-0031','00000000-0000-0000-0000-000000000004','USD',
 'Export payment – Gulf Textiles LLC – REF-2026-AE-0031',
 1275000,'INT-REF-AE-0031','2026-11-05 11:44:52.000000',
 'order_MwJP5nYB2aQsU8','PAID',0,'PAID',
 'ws-00000000-0000-0000-0000-000000000001');

-- ─────────────────────────────────────────────────────────────────────────────
-- 13. AUDIT EVENTS  (state transition trail for all transactions)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO audit_events
  (id, actor_id, actor_type, correlation_id, created_at, event_data, event_type,
   module, new_state, previous_state, transaction_id) VALUES

-- TX-001 APPROVED trail
('ae-tx1-01','00000000-0000-0000-0000-000000000004','USER','corr-DE-0045','2026-11-10 08:30:00.000000',
 '{"amount":45000,"currency":"USD","beneficiary":"Mueller Engineering GmbH","purposeCode":"P0101"}',
 'TRANSACTION_CREATED','TRANSACTION',NULL,NULL,'tx-00000000-0000-0000-0000-000000000001'),
('ae-tx1-02','00000000-0000-0000-0000-000000000004','USER','corr-DE-0045','2026-11-10 08:35:00.000000',
 '{"requiredDocuments":["COMMERCIAL_INVOICE","SHIPPING_BILL","PACKING_LIST","CERTIFICATE_OF_ORIGIN"]}',
 'TRANSACTION_STATE_CHANGED','TRANSACTION','EVIDENCE_PENDING','CREATED','tx-00000000-0000-0000-0000-000000000001'),
('ae-tx1-03','00000000-0000-0000-0000-000000000004','USER','corr-DE-0045','2026-11-10 10:50:00.000000',
 '{"documentsVerified":4,"checklistSatisfied":true}',
 'TRANSACTION_STATE_CHANGED','DOCUMENT','POLICY_EVALUATION','EVIDENCE_PENDING','tx-00000000-0000-0000-0000-000000000001'),
('ae-tx1-04',NULL,'POLICY_ENGINE','corr-DE-0045','2026-11-10 11:00:00.000000',
 '{"verdict":"APPROVED","score":100,"passedRules":5,"failedRules":0}',
 'POLICY_APPROVED','POLICY_ENGINE','APPROVED','POLICY_EVALUATION','tx-00000000-0000-0000-0000-000000000001'),
('ae-tx1-05',NULL,'WEBHOOK','corr-DE-0045','2026-11-10 09:15:00.000000',
 '{"eventId":"evt_NxKQ8aBC1234def","paymentId":"pay_NxKQ8aBC1234","amount":4500000}',
 'PAYMENT_RECEIVED','WEBHOOK','PAYMENT_RECEIVED','CREATED','tx-00000000-0000-0000-0000-000000000001'),

-- TX-002 SETTLED trail
('ae-tx2-01','00000000-0000-0000-0000-000000000004','USER','corr-AE-0031','2026-11-05 11:00:00.000000',
 '{"amount":12750,"currency":"USD","beneficiary":"Gulf Textiles LLC","purposeCode":"P0102"}',
 'TRANSACTION_CREATED','TRANSACTION',NULL,NULL,'tx-00000000-0000-0000-0000-000000000002'),
('ae-tx2-02','00000000-0000-0000-0000-000000000004','USER','corr-AE-0031','2026-11-05 11:05:00.000000',
 '{"requiredDocuments":["COMMERCIAL_INVOICE","SHIPPING_BILL","PACKING_LIST"]}',
 'TRANSACTION_STATE_CHANGED','TRANSACTION','EVIDENCE_PENDING','CREATED','tx-00000000-0000-0000-0000-000000000002'),
('ae-tx2-03','00000000-0000-0000-0000-000000000004','USER','corr-AE-0031','2026-11-05 14:30:00.000000',
 '{"documentsVerified":3,"checklistSatisfied":true}',
 'TRANSACTION_STATE_CHANGED','DOCUMENT','POLICY_EVALUATION','EVIDENCE_PENDING','tx-00000000-0000-0000-0000-000000000002'),
('ae-tx2-04',NULL,'POLICY_ENGINE','corr-AE-0031','2026-11-05 15:00:00.000000',
 '{"verdict":"APPROVED","score":100,"passedRules":5,"failedRules":0}',
 'POLICY_APPROVED','POLICY_ENGINE','APPROVED','POLICY_EVALUATION','tx-00000000-0000-0000-0000-000000000002'),
('ae-tx2-05',NULL,'WEBHOOK','corr-AE-0031','2026-11-05 11:45:00.000000',
 '{"eventId":"evt_MwJP6aCD5678ghi","paymentId":"pay_MwJP6aCD5678","amount":1275000}',
 'PAYMENT_RECEIVED','WEBHOOK','PAYMENT_RECEIVED','CREATED','tx-00000000-0000-0000-0000-000000000002'),
('ae-tx2-06','00000000-0000-0000-0000-000000000003','USER','corr-AE-0031','2026-11-06 09:00:00.000000',
 '{"settlementReference":"SETL-2026-AE-0031","processor":"RAZORPAYX"}',
 'SETTLEMENT_INITIATED','SETTLEMENT','SETTLEMENT_PENDING','APPROVED','tx-00000000-0000-0000-0000-000000000002'),
('ae-tx2-07','00000000-0000-0000-0000-000000000003','USER','corr-AE-0031','2026-11-07 09:10:00.000000',
 '{"utr":"UTR20261106001234","settledAmount":12747.75,"processingFees":2.25}',
 'SETTLEMENT_COMPLETED','SETTLEMENT','SETTLED','SETTLEMENT_PENDING','tx-00000000-0000-0000-0000-000000000002'),

-- TX-003 COMPLIANCE_HOLD trail
('ae-tx3-01','00000000-0000-0000-0000-000000000004','USER','corr-AE-0062','2026-11-18 15:45:00.000000',
 '{"amount":182000,"currency":"USD","beneficiary":"Pharma Global FZE","purposeCode":"P0101"}',
 'TRANSACTION_CREATED','TRANSACTION',NULL,NULL,'tx-00000000-0000-0000-0000-000000000003'),
('ae-tx3-02','00000000-0000-0000-0000-000000000004','USER','corr-AE-0062','2026-11-18 15:50:00.000000',
 '{"requiredDocuments":["COMMERCIAL_INVOICE","SHIPPING_BILL","EXPORT_DECLARATION"]}',
 'TRANSACTION_STATE_CHANGED','TRANSACTION','EVIDENCE_PENDING','CREATED','tx-00000000-0000-0000-0000-000000000003'),
('ae-tx3-03','00000000-0000-0000-0000-000000000004','USER','corr-AE-0062','2026-11-18 16:25:00.000000',
 '{"documentsVerified":3,"checklistSatisfied":true}',
 'TRANSACTION_STATE_CHANGED','DOCUMENT','POLICY_EVALUATION','EVIDENCE_PENDING','tx-00000000-0000-0000-0000-000000000003'),
('ae-tx3-04',NULL,'POLICY_ENGINE','corr-AE-0062','2026-11-18 16:30:00.000000',
 '{"verdict":"HOLD","score":58,"passedRules":3,"failedRules":2,"violations":["PR-01","PR-03"]}',
 'POLICY_HOLD','POLICY_ENGINE','COMPLIANCE_HOLD','POLICY_EVALUATION','tx-00000000-0000-0000-0000-000000000003'),

-- TX-004 EVIDENCE_PENDING trail
('ae-tx4-01','00000000-0000-0000-0000-000000000004','USER','corr-US-0078','2026-11-25 09:00:00.000000',
 '{"amount":8900,"currency":"USD","beneficiary":"AutoParts Distributors Inc","purposeCode":"P0101"}',
 'TRANSACTION_CREATED','TRANSACTION',NULL,NULL,'tx-00000000-0000-0000-0000-000000000004'),
('ae-tx4-02','00000000-0000-0000-0000-000000000004','USER','corr-US-0078','2026-11-25 09:05:00.000000',
 '{"requiredDocuments":["COMMERCIAL_INVOICE","SHIPPING_BILL"]}',
 'TRANSACTION_STATE_CHANGED','TRANSACTION','EVIDENCE_PENDING','CREATED','tx-00000000-0000-0000-0000-000000000004'),

-- TX-005 REJECTED trail
('ae-tx5-01','00000000-0000-0000-0000-000000000004','USER','corr-XX-0051','2026-11-12 13:00:00.000000',
 '{"amount":97500,"currency":"USD","beneficiary":"Unknown Commodities Ltd","purposeCode":"P0102"}',
 'TRANSACTION_CREATED','TRANSACTION',NULL,NULL,'tx-00000000-0000-0000-0000-000000000005'),
('ae-tx5-02','00000000-0000-0000-0000-000000000004','USER','corr-XX-0051','2026-11-12 13:05:00.000000',
 '{"requiredDocuments":["COMMERCIAL_INVOICE","EXPORT_DECLARATION"]}',
 'TRANSACTION_STATE_CHANGED','TRANSACTION','EVIDENCE_PENDING','CREATED','tx-00000000-0000-0000-0000-000000000005'),
('ae-tx5-03','00000000-0000-0000-0000-000000000004','USER','corr-XX-0051','2026-11-12 15:40:00.000000',
 '{"documentsVerified":2,"checklistSatisfied":true}',
 'TRANSACTION_STATE_CHANGED','DOCUMENT','POLICY_EVALUATION','EVIDENCE_PENDING','tx-00000000-0000-0000-0000-000000000005'),
('ae-tx5-04',NULL,'POLICY_ENGINE','corr-XX-0051','2026-11-12 15:50:00.000000',
 '{"verdict":"HOLD","score":32,"passedRules":2,"failedRules":3,"violations":["PR-01","PR-03","PR-05"]}',
 'POLICY_HOLD','POLICY_ENGINE','COMPLIANCE_HOLD','POLICY_EVALUATION','tx-00000000-0000-0000-0000-000000000005'),
('ae-tx5-05','00000000-0000-0000-0000-000000000002','USER','corr-XX-0051','2026-11-13 11:25:00.000000',
 '{"decision":"REJECTED","justification":"FEMA violation + unverifiable beneficiary + AML risk","reviewDuration":"22h"}',
 'MANUAL_REVIEW_DECISION','TRANSACTION','REJECTED','COMPLIANCE_HOLD','tx-00000000-0000-0000-0000-000000000005'),
('ae-tx5-06','00000000-0000-0000-0000-000000000002','USER','corr-XX-0051','2026-11-13 11:30:00.000000',
 '{"finalState":"REJECTED","terminal":true}',
 'TRANSACTION_COMPLETED','TRANSACTION','REJECTED','REJECTED','tx-00000000-0000-0000-0000-000000000005');

-- ─────────────────────────────────────────────────────────────────────────────
-- Done — verify row counts
-- ─────────────────────────────────────────────────────────────────────────────
SELECT 'users'                AS tbl, COUNT(*) AS rows FROM users
UNION ALL SELECT 'transactions',       COUNT(*) FROM transactions
UNION ALL SELECT 'required_documents', COUNT(*) FROM required_documents
UNION ALL SELECT 'documents',          COUNT(*) FROM documents
UNION ALL SELECT 'evidence_checklists',COUNT(*) FROM evidence_checklists
UNION ALL SELECT 'policy_evaluations', COUNT(*) FROM policy_evaluations
UNION ALL SELECT 'policy_violations',  COUNT(*) FROM policy_violations
UNION ALL SELECT 'compliance_reviews', COUNT(*) FROM compliance_reviews
UNION ALL SELECT 'webhook_events',     COUNT(*) FROM webhook_events
UNION ALL SELECT 'settlements',        COUNT(*) FROM settlements
UNION ALL SELECT 'pi_workspaces',      COUNT(*) FROM pi_workspaces
UNION ALL SELECT 'pi_orders',          COUNT(*) FROM pi_orders
UNION ALL SELECT 'audit_events',       COUNT(*) FROM audit_events;
