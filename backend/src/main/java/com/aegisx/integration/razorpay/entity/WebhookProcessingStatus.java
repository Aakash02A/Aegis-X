package com.aegisx.integration.razorpay.entity;

/**
 * Granular processing status for ingested webhook events.
 */
public enum WebhookProcessingStatus {
    RECEIVED,
    PROCESSED,
    FAILED,
    DUPLICATE,
    IGNORED
}
