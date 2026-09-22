package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookDispatcherTest {

    @Mock
    private WebhookEventRepository webhookEventRepository;

    @Mock
    private PaymentCapturedHandler paymentCapturedHandler;

    private UnsupportedEventHandler fallbackHandler;
    private ObjectMapper objectMapper;
    private RazorpayWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        fallbackHandler = new UnsupportedEventHandler();
        objectMapper = new ObjectMapper();
        List<RazorpayEventHandler> handlers = Collections.singletonList(paymentCapturedHandler);

        dispatcher = new RazorpayWebhookDispatcher(
                handlers,
                fallbackHandler,
                webhookEventRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("Idempotency: duplicate eventId skips handler execution")
    void testDuplicateEventSkipsExecution() {
        String rawPayload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_dup123\"}}}}";
        WebhookEvent existingEvent = WebhookEvent.builder()
                .eventId("pay_dup123-payment.captured")
                .processingStatus(WebhookProcessingStatus.PROCESSED)
                .build();

        when(webhookEventRepository.findByEventId("pay_dup123-payment.captured"))
                .thenReturn(Optional.of(existingEvent));

        dispatcher.dispatch(rawPayload, "WHK-IDEMPOTENT");

        verify(paymentCapturedHandler, never()).handle(any(), any(), any());
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Malformed JSON payload records FAILED event and skips dispatch")
    void testMalformedJsonRecordsFailedEvent() {
        String malformedJson = "{ invalid json content: 123 ";

        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        dispatcher.dispatch(malformedJson, "WHK-MALFORMED");

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).save(captor.capture());

        WebhookEvent saved = captor.getValue();
        assertEquals("RAZORPAY", saved.getProvider());
        assertEquals("UNKNOWN", saved.getEventType());
        assertEquals(WebhookProcessingStatus.FAILED, saved.getProcessingStatus());
        verify(paymentCapturedHandler, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("Unsupported event type routes to fallback handler with IGNORED status")
    void testUnsupportedEventRoutesToFallback() {
        String rawPayload = "{\"event\":\"refund.created\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_ref123\"}}}}";

        when(webhookEventRepository.findByEventId("pay_ref123-refund.created"))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentCapturedHandler.supports("refund.created")).thenReturn(false);

        dispatcher.dispatch(rawPayload, "WHK-UNSUPPORTED");

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository, atLeastOnce()).save(captor.capture());

        WebhookEvent lastSaved = captor.getValue();
        assertEquals(WebhookProcessingStatus.IGNORED, lastSaved.getProcessingStatus());
    }
}
