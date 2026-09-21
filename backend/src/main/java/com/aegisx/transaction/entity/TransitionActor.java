package com.aegisx.transaction.entity;

/**
 * Identifies the initiating actor or subsystem triggering a transaction lifecycle transition.
 * Prepares the domain model for structured audit event logging in future phases.
 */
public enum TransitionActor {
    MERCHANT,
    SYSTEM,
    RAZORPAY,
    DOCUMENT_SERVICE,
    OCR_PIPELINE,
    SPRING_AI,
    POLICY_ENGINE,
    COMPLIANCE_OFFICER,
    ADMIN
}
