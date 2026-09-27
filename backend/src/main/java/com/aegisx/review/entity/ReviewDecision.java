package com.aegisx.review.entity;

/**
 * Review decision enumeration for compliance officer reviews.
 * Based on TAS specification Section 6 - Compliance Reviews.
 */
public enum ReviewDecision {
    APPROVED("Approved", "Transaction approved after manual review"),
    REJECTED("Rejected", "Transaction rejected after manual review");

    private final String displayName;
    private final String description;

    ReviewDecision(String displayName, String description) {
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
     * Returns true if this decision approves the transaction.
     */
    public boolean isApproval() {
        return this == APPROVED;
    }

    /**
     * Returns true if this decision rejects the transaction.
     */
    public boolean isRejection() {
        return this == REJECTED;
    }

    /**
     * Converts from string value (case-insensitive).
     */
    public static ReviewDecision fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Review decision cannot be null");
        }
        
        try {
            return ReviewDecision.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid review decision: " + value);
        }
    }
}