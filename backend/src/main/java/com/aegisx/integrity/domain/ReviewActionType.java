package com.aegisx.integrity.domain;

/** Human operator actions recorded against an exception case. */
public enum ReviewActionType {
    OPEN_CASE,
    ADD_NOTE,
    APPROVE_RECOMMENDATION,
    REJECT_RECOMMENDATION,
    EXECUTE_READ_ONLY_RECOMMENDATION,
    RECORD_RESOLUTION,
    DISMISS,
    /** Recorded by the engine, not a human, when a later run observes the issue persisting. */
    AUTO_REOPEN
}
