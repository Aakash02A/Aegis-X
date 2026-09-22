package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UnsupportedEventHandler implements RazorpayEventHandler {

    @Override
    public boolean supports(String eventType) {
        // Fallback handler
        return true;
    }

    @Override
    public void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId) {
        String eventType = payload != null ? payload.getEvent() : "UNKNOWN";
        log.info("[{}] Received unsupported or unhandled event type '{}'; recording as IGNORED",
                correlationId, eventType);

        event.setProcessingStatus(WebhookProcessingStatus.IGNORED);
    }
}
