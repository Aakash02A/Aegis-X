package com.aegisx.common.exception;

/**
 * Thrown when a document format is not supported for OCR processing.
 * 
 * Supported formats: PDF, PNG, JPG, JPEG.
 * This exception is thrown before attempting any processing on unsupported types.
 */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }

    public UnsupportedDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
