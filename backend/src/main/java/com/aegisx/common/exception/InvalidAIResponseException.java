package com.aegisx.common.exception;

/**
 * InvalidAIResponseException indicates malformed or invalid AI response data.
 * 
 * Purpose:
 * - Signal JSON parsing or validation failures from AI response
 * - Distinguish from infrastructure errors (timeouts, API down)
 * - Enable clear error messages for debugging and logging
 * 
 * Scenarios:
 * - AI returned non-JSON text
 * - Missing required fields in response
 * - Invalid confidence scores (NaN, infinite, out of range)
 * - Malformed findings array
 */
public class InvalidAIResponseException extends RuntimeException {
    
    public InvalidAIResponseException(String message) {
        super(message);
    }
    
    public InvalidAIResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
