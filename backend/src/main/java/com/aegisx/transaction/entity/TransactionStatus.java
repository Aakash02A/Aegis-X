package com.aegisx.transaction.entity;

/**
 * Frozen MVP lifecycle states for Aegis-X transactions.
 */
public enum TransactionStatus {
    CREATED,
    PAYMENT_RECEIVED,
    EVIDENCE_PENDING,
    OCR_PROCESSING,
    AI_ANALYSIS,
    POLICY_EVALUATION,
    APPROVED,
    COMPLIANCE_HOLD
}
