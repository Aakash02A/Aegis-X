package com.aegisx.transaction.entity;

/**
 * Identifies the initiating actor or subsystem triggering a transaction lifecycle transition.
 * Used for structured audit event logging and state transition attribution.
 */
public enum TransitionActor {
    MERCHANT,
    SYSTEM,
    RAZORPAY_GATEWAY,
    DOCUMENT_SERVICE,
    OCR_SERVICE,
    AI_SERVICE,
    POLICY_ENGINE,
    COMPLIANCE_OFFICER,
    SETTLEMENT_OPERATOR,
    ADMIN,
    EVIDENCE_SERVICE,
    NOTIFICATION_SERVICE
}
