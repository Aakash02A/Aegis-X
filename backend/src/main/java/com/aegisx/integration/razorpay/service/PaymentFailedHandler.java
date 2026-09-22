package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentFailedHandler implements RazorpayEventHandler {

    public static final String EVENT_TYPE = "payment.failed";

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId) {
        String paymentId = payload.getPaymentId();
        String referenceHint = payload.getTransactionReferenceFromNotes();
        if (referenceHint == null) {
            referenceHint = payload.getOrderId();
        }

        event.setPaymentId(paymentId);
        event.setTransactionReference(referenceHint);
        event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);

        String errorCode = null;
        String errorDescription = null;
        if (payload.getPayload() != null && payload.getPayload().getPayment() != null
                && payload.getPayload().getPayment().getEntity() != null) {
            errorCode = payload.getPayload().getPayment().getEntity().getErrorCode();
            errorDescription = payload.getPayload().getPayment().getEntity().getErrorDescription();
        }

        log.warn("[{}] Recorded '{}' for paymentId: '{}', reference: '{}', errorCode: '{}', reason: '{}'",
                correlationId, EVENT_TYPE, paymentId, referenceHint, errorCode, errorDescription);
    }
}
