package com.aegisx.policy.entity;

/**
 * Policy Engine verdict enumeration.
 * Based on TAS specification Section 6 - Policy Evaluations.
 */
public enum PolicyVerdict {
    APPROVED("Approved", "All compliance rules passed, transaction may proceed to settlement"),
    HOLD("Hold", "One or more compliance rules failed, requires human review");

    private final String displayName;
    private final String description;

    PolicyVerdict(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Returns true if this verdict allows automatic processing.
     */
    public boolean allowsAutomaticProcessing() {
        return this == APPROVED;
    }

    /**
     * Returns true if this verdict requires human intervention.
     */
    public boolean requiresHumanReview() {
        return this == HOLD;
    }

    /**
     * Converts from legacy string values for backward compatibility.
     */
    public static PolicyVerdict fromLegacyDecision(String decision) {
        if (decision == null) {
            throw new IllegalArgumentException("Decision cannot be null");
        }
        
        return switch (decision.toUpperCase()) {
            case "APPROVED" -> APPROVED;
            case "COMPLIANCE_HOLD", "HOLD" -> HOLD;
            default -> throw new IllegalArgumentException("Invalid policy decision: " + decision);
        };
    }
}