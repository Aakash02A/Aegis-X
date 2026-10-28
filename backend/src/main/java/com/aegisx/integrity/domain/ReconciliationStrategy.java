package com.aegisx.integrity.domain;

/**
 * The decision procedure a reconciliation run used.
 *
 * <p>All three strategies are given the same evidence for a scenario. This is what makes the
 * benchmark comparison meaningful: only the decision procedure differs, not the inputs.</p>
 */
public enum ReconciliationStrategy {
    /** Direct comparison of internal status/amount against the latest observed provider value. */
    BASELINE_1_DIRECT_COMPARISON,
    /** Duplicate protection + basic allow-list state model + key/amount matching. */
    BASELINE_2_RULES_ENGINE,
    /** Evidence ranking + full transition history + stale/conflict detection (this project's method). */
    PROPOSED_EVIDENCE_BASED
}
