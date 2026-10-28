package com.aegisx.integrity.state;

import com.aegisx.integrity.domain.EvidenceSource;

/**
 * Outcome of asking the state machine to accept one observation.
 *
 * <p>Three distinct outcomes exist, and keeping them distinct is the whole point of the module:</p>
 * <ul>
 *   <li><strong>applied</strong> — the observation became the new canonical state.</li>
 *   <li><strong>conflict</strong> — the observation disagrees with the current state. It is always
 *       recorded as evidence; it is applied only in the one documented escape hatch (a provider API
 *       lookup contradicting a previous failure).</li>
 *   <li><strong>rejected</strong> — the observation is not provider-authoritative and describes an
 *       illegal jump, so it is dropped and reported rather than silently swallowed.</li>
 * </ul>
 *
 * @param applied       whether the canonical state changed
 * @param conflict      whether this observation contradicted the existing state
 * @param rejected      whether the observation was neither applicable nor a recordable conflict
 * @param ignored       whether the observation simply restated the current state
 * @param previousState canonical state before the decision
 * @param newState      canonical state after the decision (equal to previous when not applied)
 * @param reason        human-readable explanation, persisted in the transition history
 */
public record TransitionDecision(
        boolean applied,
        boolean conflict,
        boolean rejected,
        boolean ignored,
        String previousState,
        String newState,
        String reason,
        EvidenceSource evidenceSource) {

    public static TransitionDecision ignored(String state, EvidenceSource source) {
        return new TransitionDecision(false, false, false, true, state, state,
                "Observation restates the current state", source);
    }

    public static TransitionDecision applied(String previous, String next, String reason, EvidenceSource source) {
        return new TransitionDecision(true, false, false, false, previous, next, reason, source);
    }

    public static TransitionDecision conflict(String previous, String observed, boolean alsoApplied,
                                              String reason, EvidenceSource source) {
        return new TransitionDecision(alsoApplied, true, false, false, previous,
                alsoApplied ? observed : previous, reason, source);
    }

    public static TransitionDecision rejected(String previous, String observed, String reason, EvidenceSource source) {
        return new TransitionDecision(false, false, true, false, previous, previous, reason, source);
    }
}
