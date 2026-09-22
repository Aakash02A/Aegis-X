package com.aegisx.common.exception;

/**
 * Thrown when OCR processing fails.
 * 
 * This exception indicates a failure during text extraction or structured
 * extraction that cannot be recovered. The document status should be marked
 * as OCR_FAILED.
 */
public class OcrProcessingException extends RuntimeException {

    public OcrProcessingException(String message) {
        super(message);
    }

    public OcrProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public OcrProcessingException(Throwable cause) {
        super(cause);
    }
}
