package com.aegisx.audit.entity;

/**
 * Enumeration of business event types recorded in the audit trail.
 * Each event represents a significant state change or action across all modules.
 */
public enum AuditEventType {
    // Authentication & User Management
    USER_REGISTERED,
    USER_LOGIN,
    USER_LOGOUT,
    USER_PASSWORD_CHANGED,

    // Transaction Lifecycle
    TRANSACTION_CREATED,
    TRANSACTION_STATE_CHANGED,
    PAYMENT_RECEIVED,
    PAYMENT_RECONCILED,
    TRANSACTION_COMPLETED,
    TRANSACTION_FAILED,

    // Evidence & Document Management
    DOCUMENT_UPLOADED,
    DOCUMENT_REJECTED,
    DOCUMENT_VERIFIED,

    // OCR Processing
    OCR_STARTED,
    OCR_COMPLETED,
    OCR_FAILED,

    // AI Analysis
    AI_ANALYSIS_STARTED,
    AI_ANALYSIS_COMPLETED,
    AI_ANALYSIS_FAILED,

    // Policy Engine Decisions
    POLICY_EVALUATION_STARTED,
    POLICY_APPROVED,
    POLICY_HOLD,
    POLICY_REJECTED,
    POLICY_RULE_TRIGGERED,

    // WhatsApp & Notifications
    WHATSAPP_MESSAGE_SENT,
    WHATSAPP_MESSAGE_DELIVERED,
    WHATSAPP_MESSAGE_READ,
    WHATSAPP_MESSAGE_RECEIVED,
    WHATSAPP_MEDIA_RECEIVED,

    // Webhook Events
    WEBHOOK_RECEIVED,
    WEBHOOK_PROCESSED,
    WEBHOOK_FAILED,
    WEBHOOK_SIGNATURE_VERIFIED,

    // Manual Review
    MANUAL_REVIEW_NOTE_ADDED,

    // System & Audit
    AUDIT_ERROR,
    SYSTEM_EVENT
}
