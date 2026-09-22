package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentFailedHandlerTest {

    private final PaymentFailedHandler handler = new PaymentFailedHandler();

    @Test
    @DisplayName("PaymentFailedHandler supports payment.failed event")
    void testSupportsPaymentFailed() {
        assertTrue(handler.supports("payment.failed"));
    }

    @Test
    @DisplayName("PaymentFailedHandler sets paymentId, reference, and PROCESSED status")
    void testHandlePaymentFailed() {
        RazorpayWebhookPayload payload = RazorpayWebhookPayload.builder()
                .event("payment.failed")
                .payload(RazorpayWebhookPayload.PayloadContainer.builder()
                        .payment(RazorpayWebhookPayload.PaymentWrapper.builder()
                                .entity(RazorpayWebhookPayload.PaymentEntity.builder()
                                        .id("pay_fail_999")
                                        .errorCode("BAD_REQUEST_ERROR")
                                        .errorDescription("Card declined by issuer")
                                        .notes(Map.of("transaction_reference", "TXN-20260921-A1B2C3"))
                                        .build())
                                .build())
                        .build())
                .build();

        WebhookEvent event = WebhookEvent.builder().build();

        handler.handle(payload, event, "WHK-FAIL01");

        assertEquals("pay_fail_999", event.getPaymentId());
        assertEquals("TXN-20260921-A1B2C3", event.getTransactionReference());
        assertEquals(WebhookProcessingStatus.PROCESSED, event.getProcessingStatus());
    }
}
