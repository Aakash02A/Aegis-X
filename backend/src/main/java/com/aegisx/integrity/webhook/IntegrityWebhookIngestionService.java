package com.aegisx.integrity.webhook;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.domain.WebhookProcessingState;
import com.aegisx.integrity.entity.IntegrityWebhookEvent;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.WebhookDelivery;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.provider.PaymentProviderAdapter;
import com.aegisx.integrity.provider.ProviderAdapterRegistry;
import com.aegisx.integrity.repository.IntegrityWebhookEventRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.WebhookDeliveryRepository;
import com.aegisx.integrity.repository.WorkspaceRepository;
import com.aegisx.integrity.service.ObservationService;
import com.aegisx.integrity.state.TransitionDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Module C: duplicate-safe webhook ingestion.
 *
 * <p>The contract follows the provider's documented behaviour: verify the signature over the raw
 * body first, deduplicate on the provider event id using a database unique constraint, acknowledge
 * quickly, and never assume ordering.</p>
 *
 * <p>Guarantees this class provides:</p>
 * <ul>
 *   <li><strong>No duplicate business effect.</strong> Only the first delivery of an event id
 *       applies business processing. Repeats are logged as deliveries and return success.</li>
 *   <li><strong>No financial side effect at all.</strong> Ingestion only records observations and
 *       applies lifecycle transitions. It never creates a refund or a transfer.</li>
 *   <li><strong>Ack regardless of duplicate.</strong> The provider requires a 2xx, so a repeat is
 *       acknowledged rather than rejected with an error.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
/**
 * Refunds and transfers are deliberately <em>not</em> created from webhooks: those operations
 * originate with Aegis-X, so an unknown refund or transfer is recorded as an unmatched observation
 * rather than materialised as if the engine had initiated it.
 */
public class IntegrityWebhookIngestionService {

    private final WorkspaceRepository workspaceRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final IntegrityWebhookEventRepository eventRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ProviderAdapterRegistry adapterRegistry;
    private final RazorpayWebhookParser parser;
    private final IntegrityWebhookSignatureService signatureService;
    private final ObservationService observationService;
    private final IntegrityWebhookEventReserver eventReserver;
    private final IntegrityProperties properties;

    /** Outcome of one HTTP delivery, returned to the controller and shown in the delivery log. */
    public record IngestionResult(String providerEventId, boolean duplicate, boolean applied,
                                  WebhookProcessingState state, String message) { }

    @Transactional
    public IngestionResult ingest(UUID workspaceId, String rawBody, String signature, String eventIdHeader) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IntegrityNotFoundException("Workspace " + workspaceId + " does not exist"));

        PaymentProviderAdapter adapter = adapterRegistry.forMode(workspace.getProviderMode());

        // 1. Signature first, over the exact raw body.
        if (!adapter.verifyWebhookSignature(rawBody, signature)) {
            throw new InvalidWebhookSignatureException("Invalid webhook signature");
        }

        // 2. Stable event identity. The provider supplies this in a header; if it is absent we derive
        //    a deterministic digest of the body, which still deduplicates identical repeats.
        String providerEventId = eventIdHeader != null && !eventIdHeader.isBlank()
                ? eventIdHeader
                : "sha256:" + signatureService.sha256Hex(rawBody);

        String provider = adapter.providerName();
        boolean simulated = workspace.getProviderMode().isSimulated();
        Instant now = Instant.now();

        Optional<IntegrityWebhookEvent> existing = eventRepository
                .findByWorkspaceIdAndProviderAndProviderEventId(workspaceId, provider, providerEventId);
        if (existing.isPresent()) {
            recordDelivery(workspaceId, provider, providerEventId, existing.get().getEventType(),
                    existing.get().getId(), false, true, now, null, simulated);
            log.info("Duplicate webhook delivery for event {} acknowledged without reprocessing", providerEventId);
            return new IngestionResult(providerEventId, true, false,
                    existing.get().getProcessingState(), "Duplicate delivery acknowledged; no business effect applied");
        }

        // 3. Reserve the event id. The unique constraint is the real guard: a concurrent delivery
        //    loses this insert and is handled as a duplicate rather than double-processing.
        //
        //    The insert is performed by IntegrityWebhookEventReserver in its own transaction. That is
        //    what makes the recovery below engine-independent: a constraint violation invalidates the
        //    transaction it happens in, and MySQL and PostgreSQL disagree about how far that goes
        //    (MySQL rolls back the statement, PostgreSQL aborts everything until rollback). Doing the
        //    insert in an isolated transaction means the loser's transaction is still valid, so
        //    recording the duplicate delivery and acknowledging the provider is safe on both.
        RazorpayEventPayload payload = parser.parse(rawBody, providerEventId);
        IntegrityWebhookEvent event;
        try {
            event = eventReserver.reserve(IntegrityWebhookEvent.builder()
                    .workspaceId(workspaceId)
                    .provider(provider)
                    .providerEventId(providerEventId)
                    .eventType(payload.eventType())
                    .signatureVerified(true)
                    .processingState(WebhookProcessingState.RECEIVED)
                    .providerResourceId(firstNonNull(payload.providerPaymentId(), payload.providerOrderId(),
                            payload.providerRefundId(), payload.providerTransferId()))
                    .providerCreatedAt(payload.providerCreatedAt())
                    .receivedAt(now)
                    .payload(truncate(rawBody))
                    .simulated(simulated)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent delivery of event {} lost the insert race and is treated as a duplicate", providerEventId);

            // The winner committed — a duplicate-key error is only raised once the competing row is
            // committed — so it is visible here under READ COMMITTED. The lookup is defensive rather
            // than load-bearing.
            IntegrityWebhookEvent winner = eventRepository
                    .findByWorkspaceIdAndProviderAndProviderEventId(workspaceId, provider, providerEventId)
                    .orElse(null);
            if (winner != null) {
                recordDelivery(workspaceId, provider, providerEventId, winner.getEventType(),
                        winner.getId(), false, true, now, signatureService.sha256Hex(rawBody), simulated);
            } else {
                // A duplicate-key error implies the competing row is committed, so this is not
                // expected. It cannot be logged as a delivery either: pi_webhook_deliveries.event_id
                // is a non-null foreign key, and inventing one would be worse than the gap. Warn and
                // still acknowledge, so the provider is never asked to redeliver an accepted event.
                log.warn("Duplicate webhook event {} has no visible booking row; no delivery logged",
                        providerEventId);
            }

            return new IngestionResult(providerEventId, true, false,
                    WebhookProcessingState.DUPLICATE, "Concurrent duplicate delivery acknowledged");
        }

        recordDelivery(workspaceId, provider, providerEventId, payload.eventType(), event.getId(),
                true, true, now, signatureService.sha256Hex(rawBody), simulated);

        // 4. Apply the business effect for the first delivery only.
        WebhookProcessingState state;
        String message;
        try {
            boolean ignored = applyEvent(workspace, payload, simulated);
            state = ignored ? WebhookProcessingState.IGNORED_TERMINAL : WebhookProcessingState.PROCESSED;
            message = ignored
                    ? "Event recorded but not applied: it contradicted a terminal state"
                    : "Event accepted and applied";
        } catch (RuntimeException e) {
            log.warn("Webhook processing failed for event {}: {}", providerEventId, e.getClass().getSimpleName());
            event.setProcessingState(WebhookProcessingState.FAILED);
            event.setErrorMessage(truncateMessage(e.getMessage()));
            event.setProcessedAt(Instant.now());
            eventRepository.save(event);
            throw e;
        }

        event.setProcessingState(state);
        event.setProcessedAt(Instant.now());
        event.setDuplicateOf(null);
        eventRepository.save(event);

        return new IngestionResult(providerEventId, false, true, state, message);
    }

    /**
     * Records observations and applies transitions. Returns true when the event contradicted a
     * terminal state and was deliberately not applied.
     */
    private boolean applyEvent(Workspace workspace, RazorpayEventPayload payload, boolean simulated) {
        UUID workspaceId = workspace.getId();
        String actor = "WEBHOOK:" + payload.eventType();
        // Held in an array so it can be updated from the lambda triggered by the order lookup.
        boolean[] contradictedTerminal = {false};

        if (payload.hasPayment()) {
            Optional<PaymentRecord> payment = paymentRepository
                    .findByWorkspaceIdAndProviderPaymentId(workspaceId, payload.providerPaymentId());
            PaymentState observedState = parsePayment(payload.state());
            if (payment.isPresent()) {
                PaymentRecord record = payment.get();
                observationService.recordObservation(workspaceId, ResourceType.PAYMENT, record.getId(),
                        payload.providerPaymentId(), EvidenceSource.PROVIDER_WEBHOOK,
                        String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                        null, payload.providerCreatedAt(), simulated);
                TransitionDecision decision = observationService.applyPaymentObservation(
                        record, observedState, EvidenceSource.PROVIDER_WEBHOOK, actor);
                paymentRepository.save(record);
                contradictedTerminal[0] |= decision.conflict() && !decision.applied();

                orderRepository.findById(record.getOrderId()).ifPresent(order -> {
                    OrderState orderState = observedState.isConfirmedSuccess() ? OrderState.PAID : order.getState();
                    if (orderState != order.getState()) {
                        observationService.applyOrderObservation(order, orderState,
                                EvidenceSource.PROVIDER_WEBHOOK, actor);
                        orderRepository.save(order);
                    }
                });
            } else if (payload.hasOrder()) {
                // A payment originates with the customer, so the provider is the source of truth for
                // its existence. Aegis-X therefore creates its own record from the first delivery.
                // This is safe from duplicate creation because ingestion processes only the first
                // delivery of an event id, and (workspace, provider_payment_id) is unique.
                orderRepository.findByWorkspaceIdAndProviderOrderId(workspaceId, payload.providerOrderId())
                        .ifPresentOrElse(order -> {
                            PaymentRecord created = paymentRepository.save(PaymentRecord.builder()
                                    .workspaceId(workspaceId)
                                    .orderId(order.getId())
                                    .providerPaymentId(payload.providerPaymentId())
                                    .method(payload.method())
                                    .amountMinor(payload.amountMinor() == null ? 0L : payload.amountMinor())
                                    .currency(payload.currency() == null ? order.getCurrency() : payload.currency())
                                    .providerState(observedState)
                                    .state(PaymentState.CREATED)
                                    .stateEvidenceRank(EvidenceSource.PROVIDER_WEBHOOK.rank())
                                    .simulated(simulated)
                                    .firstObservedAt(payload.providerCreatedAt())
                                    .lastObservedAt(payload.providerCreatedAt())
                                    .build());
                            observationService.recordObservation(workspaceId, ResourceType.PAYMENT, created.getId(),
                                    payload.providerPaymentId(), EvidenceSource.PROVIDER_WEBHOOK,
                                    String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                                    null, payload.providerCreatedAt(), simulated);
                            TransitionDecision decision = observationService.applyPaymentObservation(
                                    created, observedState, EvidenceSource.PROVIDER_WEBHOOK, actor);
                            paymentRepository.save(created);
                            contradictedTerminal[0] |= decision.conflict() && !decision.applied();

                            OrderState orderState = observedState.isConfirmedSuccess() ? OrderState.PAID : order.getState();
                            if (orderState != order.getState()) {
                                observationService.applyOrderObservation(order, orderState,
                                        EvidenceSource.PROVIDER_WEBHOOK, actor);
                                orderRepository.save(order);
                            }
                        }, () -> observationService.recordOrphanObservation(workspaceId, ResourceType.PAYMENT,
                                payload.providerPaymentId(), EvidenceSource.PROVIDER_WEBHOOK,
                                String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                                payload.providerCreatedAt(), simulated));
            } else {
                observationService.recordOrphanObservation(workspaceId, ResourceType.PAYMENT,
                        payload.providerPaymentId(), EvidenceSource.PROVIDER_WEBHOOK,
                        String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                        payload.providerCreatedAt(), simulated);
            }
        }

        if (payload.hasRefund()) {
            Optional<RefundRecord> refund = refundRepository
                    .findByWorkspaceIdAndProviderRefundId(workspaceId, payload.providerRefundId());
            RefundState observedState = parseRefund(payload.state());
            if (refund.isPresent()) {
                RefundRecord record = refund.get();
                observationService.recordObservation(workspaceId, ResourceType.REFUND, record.getId(),
                        payload.providerRefundId(), EvidenceSource.PROVIDER_WEBHOOK,
                        String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                        null, payload.providerCreatedAt(), simulated);
                TransitionDecision decision = observationService.applyRefundObservation(
                        record, observedState, EvidenceSource.PROVIDER_WEBHOOK, actor);
                refundRepository.save(record);
                contradictedTerminal[0] |= decision.conflict() && !decision.applied();
            } else {
                observationService.recordOrphanObservation(workspaceId, ResourceType.REFUND,
                        payload.providerRefundId(), EvidenceSource.PROVIDER_WEBHOOK,
                        String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                        payload.providerCreatedAt(), simulated);
            }
        }

        if (!payload.hasPayment() && payload.hasOrder()) {
            orderRepository.findByWorkspaceIdAndProviderOrderId(workspaceId, payload.providerOrderId())
                    .ifPresent(order -> {
                        OrderState observedState = parseOrder(payload.state());
                        observationService.recordObservation(workspaceId, ResourceType.ORDER, order.getId(),
                                payload.providerOrderId(), EvidenceSource.PROVIDER_WEBHOOK,
                                String.valueOf(observedState), payload.amountMinor(), payload.currency(),
                                null, payload.providerCreatedAt(), simulated);
                        TransitionDecision decision = observationService.applyOrderObservation(
                                order, observedState, EvidenceSource.PROVIDER_WEBHOOK, actor);
                        orderRepository.save(order);
                    });
        }

        return contradictedTerminal[0];
    }

    private void recordDelivery(UUID workspaceId, String provider, String providerEventId, String eventType,
                                UUID eventId, boolean firstDelivery, boolean signatureVerified, Instant receivedAt,
                                String bodySha256, boolean simulated) {
        deliveryRepository.save(WebhookDelivery.builder()
                .workspaceId(workspaceId)
                .provider(provider)
                .providerEventId(providerEventId)
                .eventType(eventType == null ? "unknown" : eventType)
                .eventId(eventId)
                .firstDelivery(firstDelivery)
                .signatureVerified(signatureVerified)
                .receivedAt(receivedAt)
                .bodySha256(bodySha256)
                .simulated(simulated)
                .build());
    }

    private String truncate(String payload) {
        if (payload == null) {
            return null;
        }
        int limit = Math.max(256, properties.getMaxStoredPayloadBytes());
        if (payload.getBytes(StandardCharsets.UTF_8).length <= limit) {
            return payload;
        }
        return payload.substring(0, limit) + "...[TRUNCATED]";
    }

    private static String truncateMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static PaymentState parsePayment(String state) {
        if (state == null) {
            return PaymentState.CREATED;
        }
        try {
            return PaymentState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PaymentState.CREATED;
        }
    }

    private static RefundState parseRefund(String state) {
        if (state == null) {
            return RefundState.PENDING;
        }
        try {
            return RefundState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RefundState.PENDING;
        }
    }

    private static OrderState parseOrder(String state) {
        if (state == null) {
            return OrderState.CREATED;
        }
        try {
            return OrderState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OrderState.CREATED;
        }
    }

    /** Convenience map used by the controller's success response. */
    public static Map<String, Object> toResponse(IngestionResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("providerEventId", result.providerEventId());
        body.put("duplicate", result.duplicate());
        body.put("applied", result.applied());
        body.put("processingState", result.state());
        body.put("message", result.message());
        return body;
    }
}
