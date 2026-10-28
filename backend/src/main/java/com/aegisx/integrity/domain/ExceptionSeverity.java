package com.aegisx.integrity.domain;

/** Operational urgency of an exception. Severity drives queue ordering, never automation rights. */
public enum ExceptionSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
