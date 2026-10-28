package com.aegisx.integrity.reconciliation.model;

import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.domain.WebhookProcessingState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The complete evidence set for one reconciliation pass.
 *
 * <p>This type exists so that the three benchmarked methods receive <em>identical inputs</em>. The
 * production reconciliation service builds a pack from the database; the benchmark builds one from
 * a generated scenario. Both feed the same comparators, which means the benchmark exercises the
 * real decision logic rather than a copy of it.</p>
 */
public record EvidencePack(
        UUID workspaceId,
        Instant evaluatedAt,
        Duration stalenessThreshold,
        boolean transferCapabilityEnabled,
        boolean providerReachable,
        List<OrderView> orders,
        List<PaymentView> payments,
        List<RefundView> refunds,
        List<TransferView> transfers,
        List<ObservationView> observations,
        List<TransitionView> transitions,
        List<WebhookObservation> webhookTimeline) {

    public EvidencePack {
        orders = orders == null ? List.of() : List.copyOf(orders);
        payments = payments == null ? List.of() : List.copyOf(payments);
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
        transfers = transfers == null ? List.of() : List.copyOf(transfers);
        observations = observations == null ? List.of() : List.copyOf(observations);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        webhookTimeline = webhookTimeline == null ? List.of() : List.copyOf(webhookTimeline);
    }

    /**
     * Total order over observations: strongest evidence first, then most recent.
     *
     * <p>A tie on rank and timestamp is possible in real data. Provider webhook observations carry
     * the event's {@code created_at}, which has second precision, so two events for one payment can
     * legitimately share an observation time. Selecting "the" observation with only those two keys
     * made the verdict depend on the order the database happened to return rows in, which is how the
     * same evidence could be classified differently between runs. The trailing keys do not claim one
     * tied value is fresher; they make the choice reproducible.</p>
     *
     * <p>The tail covers <em>every</em> field that can differ between two rows — source, amount,
     * currency and finally the row identity — so the order is genuinely total. Comparing only rank,
     * time, resource id and state still returned 0 for two rows that shared those but disagreed on
     * amount, and a stable sort then preserved the caller's list order: the same non-determinism one
     * level down.</p>
     */
    public static java.util.Comparator<ObservationView> evidenceStrength() {
        return java.util.Comparator
                .comparingInt(ObservationView::rank)
                .thenComparing(ObservationView::observedAt)
                .thenComparing(ObservationView::providerResourceId, NULL_SAFE_STRINGS)
                .thenComparing(ObservationView::state, NULL_SAFE_STRINGS)
                .thenComparing(identityTieBreak());
    }

    private static final java.util.Comparator<String> NULL_SAFE_STRINGS =
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder());

    /**
     * Final, unique-ish tie-break over the remaining fields of an observation.
     *
     * <p>Only reached by rows that are equal on every decision-relevant key. It exists so that
     * {@link #evidenceStrength()} is a total order over the fields the record carries, not so that
     * any particular tied row "wins".</p>
     */
    private static java.util.Comparator<ObservationView> identityTieBreak() {
        return java.util.Comparator
                .comparing(ObservationView::source,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(ObservationView::amountMinor,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(ObservationView::currency, NULL_SAFE_STRINGS)
                .thenComparing(ObservationView::entityId,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
    }

    /**
     * Total order over observations by recency, with the same deterministic tie-break as
     * {@link #evidenceStrength()}. Used where the question is "what happened last", not "what is
     * most authoritative".
     */
    public static java.util.Comparator<ObservationView> recency() {
        return java.util.Comparator
                .comparing(ObservationView::observedAt)
                .thenComparingInt(ObservationView::rank)
                .thenComparing(ObservationView::providerResourceId, NULL_SAFE_STRINGS)
                .thenComparing(ObservationView::state, NULL_SAFE_STRINGS)
                .thenComparing(identityTieBreak());
    }

    /** Highest-ranked observation of a resource, or empty when none was captured. */
    public java.util.Optional<ObservationView> bestObservation(ResourceType type, UUID entityId) {
        return observations.stream()
                .filter(o -> o.resourceType() == type && entityId.equals(o.entityId()))
                .max(evidenceStrength());
    }

    /** Most recently observed value of a resource regardless of evidence rank. */
    public java.util.Optional<ObservationView> latestObservation(ResourceType type, UUID entityId) {
        return observations.stream()
                .filter(o -> o.resourceType() == type && entityId.equals(o.entityId()))
                .max(recency());
    }

    public List<ObservationView> observationsOf(ResourceType type, UUID entityId) {
        return observations.stream()
                .filter(o -> o.resourceType() == type && entityId.equals(o.entityId()))
                .toList();
    }

    // ------------------------------------------------------------------ views

    public record OrderView(
            UUID orderId,
            String reference,
            String providerOrderId,
            long expectedAmountMinor,
            String currency,
            OrderState internalState,
            OrderState providerState,
            Instant createdAt,
            Instant lastObservedAt,
            boolean simulated) { }

    public record PaymentView(
            UUID paymentId,
            UUID orderId,
            String providerPaymentId,
            long amountMinor,
            String currency,
            PaymentState internalState,
            PaymentState providerState,
            int stateEvidenceRank,
            Instant firstObservedAt,
            Instant lastObservedAt,
            boolean simulated) { }

    public record RefundView(
            UUID refundId,
            UUID paymentId,
            String providerRefundId,
            long amountMinor,
            String currency,
            RefundState state,
            Instant lastObservedAt,
            boolean simulated) { }

    public record TransferView(
            UUID transferId,
            UUID paymentId,
            UUID orderId,
            String providerTransferId,
            long amountMinor,
            String currency,
            TransferState state,
            Instant lastObservedAt,
            boolean simulated) { }

    public record ObservationView(
            UUID entityId,
            ResourceType resourceType,
            String providerResourceId,
            EvidenceSource source,
            int rank,
            String state,
            Long amountMinor,
            String currency,
            Instant observedAt,
            boolean simulated) { }

    public record TransitionView(
            ResourceType entityType,
            UUID entityId,
            String fromState,
            String toState,
            boolean conflict,
            boolean providerConfirmed,
            Instant occurredAt,
            String reason) { }

    public record WebhookObservation(
            String providerEventId,
            String eventType,
            String providerResourceId,
            WebhookProcessingState processingState,
            Instant providerCreatedAt,
            Instant receivedAt,
            boolean duplicateOfEarlierDelivery) { }
}
