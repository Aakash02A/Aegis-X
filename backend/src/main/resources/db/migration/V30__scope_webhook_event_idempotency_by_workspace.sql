-- ============================================================================
-- V30: scope webhook event idempotency by workspace
--
-- The idempotency guard was UNIQUE (provider, provider_event_id). Provider event ids are allocated
-- per provider ACCOUNT, not per workspace: Razorpay's x-razorpay-event-id is unique for the whole
-- account. Two workspaces bound to the same provider account therefore competed for one row — the
-- second workspace's delivery hit the constraint, was treated as a duplicate, was acknowledged with
-- 2xx and was never applied. The provider never redelivers an acknowledged event, so the loss was
-- silent: a payment confirmation or refund could be missing from one workspace's ledger.
--
-- The dedupe key becomes (workspace_id, provider, provider_event_id): a repeat delivery to the same
-- workspace is still rejected by the database, which is what replay protection requires, while a
-- different workspace gets its own row.
--
-- The replacement index serves the lookup directly (workspace first, then the event identity), so
-- the old secondary index is not needed.
-- ============================================================================

ALTER TABLE pi_webhook_events
    DROP INDEX uq_pi_webhook_events_provider_event;

ALTER TABLE pi_webhook_events
    ADD CONSTRAINT uq_pi_webhook_events_workspace_provider_event
        UNIQUE (workspace_id, provider, provider_event_id);
