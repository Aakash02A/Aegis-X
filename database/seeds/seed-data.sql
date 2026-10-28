-- ============================================================================
-- Aegis-X Seed Data for Local Development & Testing Only
-- ⚠️  NEVER EXECUTE THIS SCRIPT IN A PRODUCTION ENVIRONMENT!
--     Default credentials are publicly known test fixtures.
-- ============================================================================
-- Compatible with MySQL 8.4 LTS (utf8mb4_0900_ai_ci, UTC timestamps)

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 1. Demo Workspace
INSERT INTO `integrity_workspaces` (`id`, `name`, `status`, `created_at`, `updated_at`)
VALUES
  ('ws_demo_01', 'Acme Global Trade Exports', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 2. Initial Administrative & Demo Accounts
-- Passwords hashed with BCrypt (strength 12) for 'Password123!'
-- Hash: $2a$12$e8hFzX7jS9QcKzP5uW2PCOe3Gf1tY9X9y2M8m7T6r5K4j3H2g1Fe
INSERT INTO `users` (`id`, `email`, `password`, `full_name`, `phone`, `role`, `created_at`, `updated_at`)
VALUES
  (1001, 'admin@aegisx.io', '$2a$12$6/YdJpD9yTq/7WqBqX1i3.lXfM9m3yF3yTq/7WqBqX1i3.lXfM9m3', 'Aegis System Admin', '+919999900001', 'ADMIN', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  (1002, 'compliance@aegisx.io', '$2a$12$6/YdJpD9yTq/7WqBqX1i3.lXfM9m3yF3yTq/7WqBqX1i3.lXfM9m3', 'Lead Compliance Officer', '+919999900002', 'COMPLIANCE_OFFICER', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  (1003, 'settlement@aegisx.io', '$2a$12$6/YdJpD9yTq/7WqBqX1i3.lXfM9m3yF3yTq/7WqBqX1i3.lXfM9m3', 'Settlement Operations Lead', '+919999900003', 'SETTLEMENT_OPERATOR', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
  (1004, 'exporter@acmetrade.com', '$2a$12$6/YdJpD9yTq/7WqBqX1i3.lXfM9m3yF3yTq/7WqBqX1i3.lXfM9m3', 'Acme Trade Exporter', '+919999900004', 'MERCHANT', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE `full_name` = VALUES(`full_name`);

-- 3. Initial Baseline Rules
INSERT INTO `policy_evaluations` (`id`, `transaction_id`, `rule_code`, `passed`, `evaluation_details`, `created_at`)
VALUES
  (1, NULL, 'FEMA_EDPMS_MANDATE', TRUE, '{"rule": "Valid trade invoice must accompany inward cross-border remittances exceeding 10,000 USD"}', CURRENT_TIMESTAMP(6)),
  (2, NULL, 'SANCTION_OFAC_SCREENING', TRUE, '{"rule": "Beneficiary and originating jurisdiction must not appear on active OFAC/UN sanction lists"}', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE `passed` = VALUES(`passed`);

