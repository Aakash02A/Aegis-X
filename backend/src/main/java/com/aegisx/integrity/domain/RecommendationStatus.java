package com.aegisx.integrity.domain;

/** Lifecycle of a recovery recommendation. */
public enum RecommendationStatus {
    PROPOSED,
    APPROVED,
    REJECTED,
    EXECUTED,
    FAILED,
    /** A human approved a financial action, but the prototype is configured to refuse to execute it. */
    EXECUTION_DISABLED
}
