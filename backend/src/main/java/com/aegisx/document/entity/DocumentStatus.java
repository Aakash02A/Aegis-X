package com.aegisx.document.entity;

/**
 * Tracks the processing lifecycle of a document.
 * Phase 07 uses UPLOADED only. Phase 08 introduces OCR states.
 */
public enum DocumentStatus {
    UPLOADED,
    OCR_PROCESSING,
    OCR_COMPLETED,
    OCR_FAILED
}
