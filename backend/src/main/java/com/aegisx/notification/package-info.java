/**
 * Notification module for WhatsApp and external integrations.
 * 
 * Purpose:
 * - Manage communication events (outbound requests, inbound responses)
 * - Track delivery status through Meta webhook events
 * - Store immutable audit trail of all notifications
 * - Enable transaction mapping for inbound messages
 * 
 * Key Components:
 * - NotificationMessage: Immutable record of communication
 * - NotificationService: Outbound request orchestration
 * - WhatsAppClient: Meta API integration
 * - WhatsAppWebhookService: Inbound event processing
 * 
 * Architectural Constraints:
 * - WhatsApp is communication channel, not source of truth
 * - Every inbound message must map to existing transaction
 * - Uploaded documents flow through DocumentService
 * - AI never invoked directly from WhatsApp
 * - All messages auditable and idempotent
 */
package com.aegisx.notification;
