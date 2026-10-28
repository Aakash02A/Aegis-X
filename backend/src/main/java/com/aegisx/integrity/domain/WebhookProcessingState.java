package com.aegisx.integrity.domain;

/** Outcome of ingesting one webhook delivery. */
public enum WebhookProcessingState {
    /** Durably recorded, business processing not yet attempted. */
    RECEIVED,
    /** Business effect applied. */
    PROCESSED,
    /** This delivery was a repeat of an already-seen provider event id. */
    DUPLICATE,
    /** Business processing failed; the event row remains for retry/observability. */
    FAILED,
    /**
     * The event referenced a resource that is already in a terminal state and contradicts it,
     * so it was recorded as evidence but deliberately not applied as a transition.
     */
    IGNORED_TERMINAL
}
