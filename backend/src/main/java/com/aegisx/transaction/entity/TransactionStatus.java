package com.aegisx.transaction.entity;

/**
 * Complete lifecycle states for Aegis-X transactions according to TAS specification.
 * State transitions follow the deterministic state machine defined in SRS Section 6.
 */
public enum TransactionStatus {
    // Initial states
    CREATED,
    
    // Payment states
    PAYMENT_RECEIVED,
    PAYMENT_FAILED,
    
    // Evidence and processing states
    EVIDENCE_PENDING,
    OCR_PROCESSING,
    OCR_FAILED,
    AI_ANALYSIS,
    AI_ANALYSIS_FAILED,
    POLICY_EVALUATION,

    // Settlement lifecycle
    SETTLEMENT_PENDING,
    SETTLED,
    SETTLEMENT_FAILED,
    
    // Terminal states
    APPROVED,
    COMPLIANCE_HOLD,
    REJECTED;
    
    /**
     * Returns true if this status represents a terminal state where no further transitions are allowed.
     */
    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED || this == PAYMENT_FAILED;
    }
    
    /**
     * Returns true if this status represents a failure state that may allow recovery.
     */
    public boolean isFailureState() {
        return this == OCR_FAILED || this == AI_ANALYSIS_FAILED || this == SETTLEMENT_FAILED;
    }
    
    /**
     * Returns true if this status requires human intervention.
     */
    public boolean requiresHumanIntervention() {
        return this == COMPLIANCE_HOLD;
    }
}
