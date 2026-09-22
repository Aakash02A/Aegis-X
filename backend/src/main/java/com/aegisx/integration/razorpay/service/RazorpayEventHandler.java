package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;

/**
 * Strategy interface for handling specific Razorpay event types.
 */
public interface RazorpayEventHandler {

    /**
     * Determines whether this handler supports the given event type.
     */
    boolean supports(String eventType);

    /**
     * Executes business logic for the supported webhook event.
     *
     * @param payload       The parsed Razorpay event payload
     * @param event         The persisted WebhookEvent audit entity
     * @param correlationId Unique correlation identifier for request log tracing
     */
    void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId);
}
