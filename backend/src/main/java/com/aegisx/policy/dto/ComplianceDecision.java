package com.aegisx.policy.dto;

/**
 * ComplianceDecision enum represents the only two legal outcomes of policy evaluation.
 * 
 * APPROVED: Transaction passes all compliance rules; authorized for settlement.
 * COMPLIANCE_HOLD: Transaction fails one or more rules; requires investigation before settlement.
 * 
 * This is the only place in the system where these terminal decisions are defined.
 * The Policy Engine is the sole component that assigns these values.
 */
public enum ComplianceDecision {
    APPROVED("Transaction approved for settlement"),
    COMPLIANCE_HOLD("Transaction held pending compliance review");
    
    private final String description;
    
    ComplianceDecision(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
