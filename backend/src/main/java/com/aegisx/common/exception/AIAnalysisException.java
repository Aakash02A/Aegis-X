package com.aegisx.common.exception;

/**
 * AIAnalysisException indicates a failure in AI-assisted compliance analysis.
 * 
 * Purpose:
 * - Wrap Spring AI errors and service failures
 * - Provide context for logging and API error responses
 * - Enable graceful failure handling without losing audit history
 * 
 * Scenarios:
 * - Spring AI API unavailable
 * - Timeout during AI processing
 * - Input validation failure
 * - Unexpected service errors
 */
public class AIAnalysisException extends RuntimeException {
    
    public AIAnalysisException(String message) {
        super(message);
    }
    
    public AIAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
