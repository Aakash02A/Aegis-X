package com.aegisx.common.exception;

/**
 * MissingEvidenceException indicates that required evidence for policy evaluation is missing.
 * 
 * Purpose:
 * - Signal when critical data is absent for compliance assessment
 * - Trigger fail-closed behavior (COMPLIANCE_HOLD)
 * - Provide clear indication of what evidence is needed
 * 
 * Scenarios:
 * - OCR extraction missing or incomplete
 * - AI analysis not yet completed
 * - Required documents missing from checklist
 * - Transaction data incomplete
 */
public class MissingEvidenceException extends RuntimeException {
    
    public MissingEvidenceException(String message) {
        super(message);
    }
    
    public MissingEvidenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
