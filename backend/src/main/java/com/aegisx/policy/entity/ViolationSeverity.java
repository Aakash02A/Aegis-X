package com.aegisx.policy.entity;

/**
 * Violation severity levels for policy rule failures.
 * Based on TAS specification Section 6 - Policy Violations.
 */
public enum ViolationSeverity {
    CRITICAL("Critical", 4, "Must be addressed before approval"),
    HIGH("High", 3, "Should be reviewed by compliance officer"),
    MEDIUM("Medium", 2, "May require additional documentation"),
    LOW("Low", 1, "Informational, may not block approval");

    private final String displayName;
    private final int priorityLevel;
    private final String description;

    ViolationSeverity(String displayName, int priorityLevel, String description) {
        this.displayName = displayName;
        this.priorityLevel = priorityLevel;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPriorityLevel() {
        return priorityLevel;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Returns true if this severity level blocks automatic approval.
     */
    public boolean blocksApproval() {
        return this == CRITICAL || this == HIGH;
    }

    /**
     * Returns true if this severity level requires human review.
     */
    public boolean requiresReview() {
        return this == CRITICAL || this == HIGH;
    }

    /**
     * Compares severity levels by priority (higher number = more severe).
     */
    public boolean isMoreSevereThan(ViolationSeverity other) {
        return this.priorityLevel > other.priorityLevel;
    }

    /**
     * Gets the most severe violation from a collection.
     */
    public static ViolationSeverity getMostSevere(ViolationSeverity... severities) {
        ViolationSeverity mostSevere = LOW;
        for (ViolationSeverity severity : severities) {
            if (severity.isMoreSevereThan(mostSevere)) {
                mostSevere = severity;
            }
        }
        return mostSevere;
    }
}