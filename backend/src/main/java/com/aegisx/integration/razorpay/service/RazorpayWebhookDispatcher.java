package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayWebhookDispatcher {

    private final List<RazorpayEventHandler> eventHandlers;
    private final UnsupportedEventHandler fallbackHandler;
    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Ingests, persists, and dispatches a verified Razorpay webhook payload within a transactional boundary.
     *
     * @param rawPayload    Unmodified raw request body bytes/string
     * @param eventIdHeader Value from X-Razorpay-Event-Id header (primary idempotency key)
     * @param correlationId Generated tracing correlation ID for log clarity
     */
    @Transactional
    public void dispatch(String rawPayload, String eventIdHeader, String correlationId) {
        log.info("[{}] Dispatching verified Razorpay webhook payload", correlationId);

        RazorpayWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, RazorpayWebhookPayload.class);
        } catch (JsonProcessingException e) {
            log.error("[{}] Malformed JSON payload received in webhook; recording FAILED event", correlationId, e);
            String malformedId = (eventIdHeader != null && !eventIdHeader.isBlank())
                    ? eventIdHeader
                    : "MALFORMED-" + UUID.randomUUID();
            WebhookEvent malformedEvent = WebhookEvent.builder()
                    .provider("RAZORPAY")
                    .eventId(malformedId)
                    .eventType("UNKNOWN")
                    .payload(rawPayload)
                    .signatureVerified(true)
                    .processingStatus(WebhookProcessingStatus.FAILED)
                    .build();
            webhookEventRepository.save(malformedEvent);
            return;
        }

        // Use X-Razorpay-Event-Id header as primary idempotency key per Razorpay delivery specifications;
        // fallback to paymentId-event or UUID only if header is omitted.
        String eventId;
        if (eventIdHeader != null && !eventIdHeader.isBlank()) {
            eventId = eventIdHeader.trim();
        } else if (payload.getPaymentId() != null) {
            eventId = payload.getPaymentId() + "-" + (payload.getEvent() != null ? payload.getEvent() : "EVENT");
        } else {
            eventId = "EVT-" + UUID.randomUUID();
        }

        // 1. Idempotency Check: Has this event already been ingested?
        Optional<WebhookEvent> existingEventOpt = webhookEventRepository.findByEventId(eventId);
        if (existingEventOpt.isPresent()) {
            WebhookEvent existing = existingEventOpt.get();
            log.info("[{}] Duplicate webhook event detected (eventId: '{}', status: '{}'). Returning idempotently.",
                    correlationId, eventId, existing.getProcessingStatus());
            return;
        }


        // 2. Persist-Before-Dispatch: Save event in RECEIVED state
        Instant providerTimestamp = payload.getCreatedAt() != null
                ? Instant.ofEpochSecond(payload.getCreatedAt())
                : null;

        WebhookEvent event = WebhookEvent.builder()
                .provider("RAZORPAY")
                .eventId(eventId)
                .eventType(payload.getEvent() != null ? payload.getEvent() : "UNKNOWN")
                .payload(rawPayload)
                .signatureVerified(true)
                .processingStatus(WebhookProcessingStatus.RECEIVED)
                .providerEventTimestamp(providerTimestamp)
                .build();

        event = webhookEventRepository.save(event);
        log.info("[{}] Persisted webhook event record with ID: {} and eventId: '{}'", correlationId, event.getId(), eventId);

        // 3. Dispatch to matching typed handler
        String eventType = payload.getEvent();
        RazorpayEventHandler targetHandler = eventHandlers.stream()
                .filter(h -> !(h instanceof UnsupportedEventHandler) && h.supports(eventType))
                .findFirst()
                .orElse(fallbackHandler);

        try {
            targetHandler.handle(payload, event, correlationId);
        } catch (Exception ex) {
            log.error("[{}] Error occurred while executing handler for event '{}' (eventId: '{}')",
                    correlationId, eventType, eventId, ex);
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            webhookEventRepository.save(event);
            throw ex;
        }

        // 4. Save final state
        webhookEventRepository.save(event);
        log.info("[{}] Finished processing webhook event '{}' with final status: '{}'",
                correlationId, eventId, event.getProcessingStatus());
    }

    /**
     * Convenience overload when X-Razorpay-Event-Id header is not explicitly available.
     */
    @Transactional
    public void dispatch(String rawPayload, String correlationId) {
        dispatch(rawPayload, null, correlationId);
    }
}

