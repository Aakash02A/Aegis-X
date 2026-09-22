package com.aegisx.common.exception;

/**
 * PolicyEvaluationException indicates a failure in policy evaluation.
 * 
 * Purpose:
 * - Wrap policy engine errors and service failures
 * - Provide context for logging and API error responses
 * - Enable graceful failure handling; failing closed (HOLD) on any error
 * 
 * Scenarios:
 * - Missing transaction data
 * - Missing OCR extraction
 * - Missing AI analysis
 * - Unexpected rule engine errors
 */
public class PolicyEvaluationException extends RuntimeException {
    
    public PolicyEvaluationException(String message) {
        super(message);
    }
    
    public PolicyEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
