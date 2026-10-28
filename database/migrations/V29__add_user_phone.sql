-- ============================================================================
-- V29: merchant contact number
--
-- Outbound WhatsApp notifications used to be sent to a hardcoded placeholder number
-- (+1234567890), so no real merchant could ever receive one. The contact number belongs on the
-- account, not in a constant.
--
-- Nullable on purpose: phone is not required to register or to use the dashboard, and existing
-- accounts have no number to backfill. Stored in international format without the leading '+',
-- which is the form the WhatsApp Cloud API expects (e.g. 919820011234).
-- ============================================================================

ALTER TABLE users ADD COLUMN phone VARCHAR(32) NULL;
