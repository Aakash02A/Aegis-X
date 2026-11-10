package com.aegisx.settlement.entity;

/**
 * Enumeration of settlement statuses according to TAS specification.
 * Tracks the lifecycle of transaction settlements.
 */
public enum SettlementStatus {
    
    /**
     * Settlement request initiated but not yet processed.
     */
    INITIATED("Initiated", "Settlement request has been created", false, false),
    
    /**
     * Settlement is being processed by payment processor.
     */
    PROCESSING("Processing", "Settlement is being processed by payment system", false, false),
    
    /**
     * Settlement has been sent to the payment processor.
     */
    SUBMITTED("Submitted", "Settlement has been submitted to payment processor", false, false),
    
    /**
     * Settlement is pending confirmation from payment processor.
     */
    PENDING("Pending", "Settlement is pending confirmation", false, false),
    
    /**
     * Settlement has been successfully completed.
     */
    COMPLETED("Completed", "Settlement has been successfully processed", true, false),
    
    /**
     * Settlement failed during processing.
     */
    FAILED("Failed", "Settlement processing failed", true, true),
    
    /**
     * Settlement was rejected by payment processor.
     */
    REJECTED("Rejected", "Settlement was rejected by payment processor", true, true),
    
    /**
     * Settlement was cancelled before completion.
     */
    CANCELLED("Cancelled", "Settlement was cancelled", true, true),
    
    /**
     * Settlement requires manual intervention.
     */
    MANUAL_REVIEW("Manual Review", "Settlement requires manual review", false, false),
    
    /**
     * Settlement is being reconciled.
     */
    RECONCILING("Reconciling", "Settlement is being reconciled with bank records", false, false),
    
    /**
     * Settlement has been reconciled successfully.
     */
    RECONCILED("Reconciled", "Settlement has been reconciled with bank records", true, false),
    
    /**
     * Settlement reconciliation failed or has discrepancies.
     */
    RECONCILIATION_FAILED("Reconciliation Failed", "Settlement reconciliation failed", true, true);

    private final String displayName;
    private final String description;
    private final boolean isFinal;
    private final boolean isError;

    SettlementStatus(String displayName, String description, boolean isFinal, boolean isError) {
        this.displayName = displayName;
        this.description = description;
        this.isFinal = isFinal;
        this.isError = isError;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public boolean isError() {
        return isError;
    }

    public boolean isSuccess() {
        return isFinal && !isError;
    }

    public boolean isInProgress() {
        return !isFinal;
    }

    /**
     * Returns true if this status allows retry attempts.
     */
    public boolean allowsRetry() {
        return this == FAILED || this == REJECTED || this == RECONCILIATION_FAILED;
    }

    /**
     * Returns true if this status requires manual intervention.
     */
    public boolean requiresManualIntervention() {
        return this == MANUAL_REVIEW || this == RECONCILIATION_FAILED;
    }

    /**
     * Gets the next possible statuses from current status.
     */
    public SettlementStatus[] getNextPossibleStatuses() {
        return switch (this) {
            case INITIATED -> new SettlementStatus[]{PROCESSING, CANCELLED, MANUAL_REVIEW};
            case PROCESSING -> new SettlementStatus[]{SUBMITTED, PENDING, COMPLETED, FAILED, REJECTED, MANUAL_REVIEW};
            case SUBMITTED -> new SettlementStatus[]{PENDING, COMPLETED, FAILED, REJECTED, MANUAL_REVIEW};
            case PENDING -> new SettlementStatus[]{COMPLETED, FAILED, REJECTED, CANCELLED, MANUAL_REVIEW};
            case COMPLETED -> new SettlementStatus[]{RECONCILING};
            case RECONCILING -> new SettlementStatus[]{RECONCILED, RECONCILIATION_FAILED};
            case MANUAL_REVIEW -> new SettlementStatus[]{PROCESSING, PENDING, COMPLETED, FAILED, REJECTED, CANCELLED};
            case FAILED, REJECTED -> new SettlementStatus[]{PROCESSING, CANCELLED}; // Retry
            case RECONCILIATION_FAILED -> new SettlementStatus[]{RECONCILING, MANUAL_REVIEW};
            default -> new SettlementStatus[]{}; // Final states
        };
    }
}