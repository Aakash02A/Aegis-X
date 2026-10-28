package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RazorpayWebhookDispatcherTest {

    @Autowired private RazorpayWebhookDispatcher dispatcher;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Test
    @DisplayName("An existing event id remains idempotent and is not dispatched again")
    void duplicateEventSkipsExecution() {
        String eventId = "evt_duplicate_" + UUID.randomUUID();
        webhookEventRepository.saveAndFlush(WebhookEvent.builder()
                .provider("RAZORPAY")
                .eventId(eventId)
                .eventType("payment.captured")
                .payload("{}")
                .signatureVerified(true)
                .processingStatus(WebhookProcessingStatus.PROCESSED)
                .build());
        long eventCount = webhookEventRepository.count();

        dispatcher.dispatch(capturedPayload("TXN-DOES-NOT-EXIST"), eventId, "WHK-IDEMPOTENT");

        assertThat(webhookEventRepository.count()).isEqualTo(eventCount);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.PROCESSED);
    }

    @Test
    @DisplayName("Malformed JSON is persisted as a failed event without invoking handlers")
    void malformedJsonRecordsFailedEvent() {
        String eventId = "evt_malformed_" + UUID.randomUUID();

        dispatcher.dispatch("{ invalid json content: 123 ", eventId, "WHK-MALFORMED");

        WebhookEvent stored = webhookEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(stored.getProvider()).isEqualTo("RAZORPAY");
        assertThat(stored.getEventType()).isEqualTo("UNKNOWN");
        assertThat(stored.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("Unsupported webhook events are persisted and routed to the real fallback handler")
    void unsupportedEventRoutesToFallback() {
        String eventId = "evt_unsupported_" + UUID.randomUUID();

        dispatcher.dispatch(
                "{\"event\":\"refund.created\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_ref123\"}}}}",
                eventId, "WHK-UNSUPPORTED");

        WebhookEvent stored = webhookEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(stored.getEventType()).isEqualTo("refund.created");
        assertThat(stored.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.IGNORED);
    }

    private String capturedPayload(String reference) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{" +
                "\"id\":\"pay_" + UUID.randomUUID() + "\",\"amount\":250000,\"currency\":\"USD\"," +
                "\"notes\":{\"transaction_reference\":\"" + reference + "\"}}}}}";
    }
}
