package com.aegisx.integrity.domain;

/** Why a reconciliation run happened. */
public enum ReconciliationTrigger {
    MANUAL,
    WEBHOOK,
    SCHEDULED,
    BENCHMARK,
    /**
     * An assessment of the evidence dimension: required documents present, readable and consistent
     * with the order and the payment. Recorded as its own run so the console can show whether the
     * last thing that examined this workspace looked at the money or at the paperwork.
     */
    EVIDENCE_ASSESSMENT
}
