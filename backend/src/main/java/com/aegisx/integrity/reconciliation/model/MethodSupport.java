package com.aegisx.integrity.reconciliation.model;

import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure helpers shared by the three reconciliation methods.
 *
 * <p>All three methods build on the same primitives, so a difference in their output reflects a
 * difference in decision procedure rather than in plumbing.</p>
 */
public final class MethodSupport {

    private MethodSupport() {
    }

    /** Provider truth according to "whatever we most recently heard", ignoring evidence rank. */
    public static Optional<EvidenceSource> latestSource(EvidencePack pack, ResourceType type, UUID entityId) {
        return pack.latestObservation(type, entityId).map(EvidencePack.ObservationView::source);
    }

    /**
     * Provider truth according to the evidence hierarchy.
     *
     * <p>Delegates the ordering to {@link EvidencePack#evidenceStrength()} so that a client answer and
     * a webhook carrying the same rank and timestamp can never resolve differently on two runs.</p>
     */
    public static Optional<EvidencePack.ObservationView> bestProvider(EvidencePack pack, ResourceType type, UUID entityId) {
        return pack.observationsOf(type, entityId).stream()
                .filter(o -> o.source().isProviderAuthoritative())
                .max(EvidencePack.evidenceStrength());
    }

    /** Any observation at all, ranked, used when no provider-authoritative source exists. */
    public static Optional<EvidencePack.ObservationView> bestAny(EvidencePack pack, ResourceType type, UUID entityId) {
        return pack.bestObservation(type, entityId);
    }

    public static List<EvidencePack.ObservationView> orphanPaymentObservations(EvidencePack pack) {
        return pack.observations().stream()
                .filter(o -> o.resourceType() == ResourceType.PAYMENT && o.entityId() == null)
                .toList();
    }

    public static List<EvidencePack.ObservationView> orphanOrderObservations(EvidencePack pack) {
        return pack.observations().stream()
                .filter(o -> o.resourceType() == ResourceType.ORDER && o.entityId() == null)
                .toList();
    }

    /** Event ids delivered more than once, mapped to their delivery count. */
    public static Map<String, Integer> duplicateDeliveries(EvidencePack pack) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EvidencePack.WebhookObservation delivery : pack.webhookTimeline()) {
            if (delivery.providerEventId() == null) {
                continue;
            }
            counts.merge(delivery.providerEventId(), 1, Integer::sum);
        }
        counts.entrySet().removeIf(e -> e.getValue() < 2);
        return counts;
    }

    public static Map<UUID, List<EvidencePack.PaymentView>> paymentsByOrder(EvidencePack pack) {
        Map<UUID, List<EvidencePack.PaymentView>> byOrder = new LinkedHashMap<>();
        for (EvidencePack.PaymentView payment : pack.payments()) {
            byOrder.computeIfAbsent(payment.orderId(), k -> new java.util.ArrayList<>()).add(payment);
        }
        return byOrder;
    }

    public static Map<UUID, List<EvidencePack.RefundView>> refundsByPayment(EvidencePack pack) {
        Map<UUID, List<EvidencePack.RefundView>> byPayment = new LinkedHashMap<>();
        for (EvidencePack.RefundView refund : pack.refunds()) {
            byPayment.computeIfAbsent(refund.paymentId(), k -> new java.util.ArrayList<>()).add(refund);
        }
        return byPayment;
    }

    public static Map<UUID, List<EvidencePack.TransferView>> transfersByPayment(EvidencePack pack) {
        Map<UUID, List<EvidencePack.TransferView>> byPayment = new LinkedHashMap<>();
        for (EvidencePack.TransferView transfer : pack.transfers()) {
            if (transfer.paymentId() != null) {
                byPayment.computeIfAbsent(transfer.paymentId(), k -> new java.util.ArrayList<>()).add(transfer);
            }
        }
        return byPayment;
    }

    public static boolean olderThan(Instant moment, Instant now, Duration threshold) {
        return moment != null && now != null && moment.plus(threshold).isBefore(now);
    }

    public static long processedRefundTotal(List<EvidencePack.RefundView> refunds) {
        return refunds.stream()
                .filter(r -> r.state() == RefundState.PROCESSED || r.state() == RefundState.PENDING)
                .mapToLong(EvidencePack.RefundView::amountMinor)
                .sum();
    }

    public static boolean isPending(PaymentState state) {
        return state != null && state.isPending();
    }
}
