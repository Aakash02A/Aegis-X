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
     * The event contradicted a terminal state or its provider order id did not match the known
     * payment binding, so it was recorded as evidence but deliberately not applied as a transition.
     */
    IGNORED_TERMINAL
}
