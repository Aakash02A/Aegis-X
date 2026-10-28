package com.aegisx.integrity.domain;

/**
 * Safety class of a recovery recommendation. Only {@code READ_ONLY} may ever execute without a human.
 */
public enum RecommendationCategory {
    /** Safe to execute automatically: fetches, lookups, re-runs. No financial side effect is possible. */
    READ_ONLY,
    /** A human must decide, but the decision itself has no financial side effect. */
    HUMAN_APPROVAL_REQUIRED,
    /** Changes money. Never automatic; disabled entirely unless explicitly enabled by configuration. */
    FINANCIAL
}
