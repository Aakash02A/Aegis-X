package com.aegisx.integrity.reconciliation.method;

import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.reconciliation.model.CandidateFactory;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.reconciliation.model.MethodSupport;
import com.aegisx.integrity.reconciliation.model.ReconciliationMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <strong>Baseline 2 — conventional reconciliation rules with duplicate protection and a basic
 * state model.</strong>
 *
 * <p>This is a competent, production-shaped implementation: it deduplicates deliveries, matches on
 * provider identifiers, and compares status, amount and currency against provider-authoritative
 * observations. It is the baseline the research question actually asks about.</p>
 *
 * <p>What it still lacks is the part the proposed method adds: it has no concept of an observation
 * being <em>stale</em>, no concept of two observations <em>conflicting</em>, it does not reconcile
 * transfers, it does not cross-check provider refund records against internal ones, and it grades
 * every finding at the same certainty.</p>
 */
@Component
public class BaselineTwoMethod implements ReconciliationMethod {

    @Override
    public ReconciliationStrategy strategy() {
        return ReconciliationStrategy.BASELINE_2_RULES_ENGINE;
    }

    @Override
    public String displayName() {
        return "Baseline 2 - rules engine with dedup and basic state model";
    }

    @Override
    public List<FindingCandidate> evaluate(EvidencePack pack) {
        List<FindingCandidate> findings = new ArrayList<>();
        Map<UUID, List<EvidencePack.PaymentView>> paymentsByOrder = MethodSupport.paymentsByOrder(pack);

        detectDuplicateDeliveries(pack, findings);
        detectOrderLevelIssues(pack, paymentsByOrder, findings);
        detectPaymentLevelIssues(pack, findings);
        detectOrphanPayments(pack, findings);
        detectRefundIssues(pack, findings);

        return List.copyOf(findings);
    }

    protected void detectDuplicateDeliveries(EvidencePack pack, List<FindingCandidate> findings) {
        MethodSupport.duplicateDeliveries(pack).forEach((eventId, count) ->
                findings.add(CandidateFactory.of(
                        FindingType.DUPLICATE_EVENT_DELIVERY, CertaintyLevel.CERTAIN,
                        null, null, null, null, eventId,
                        "Provider event " + eventId + " was delivered " + count + " times",
                        List.of("deliveries observed for event id " + eventId + " = " + count,
                                "the provider documents at-least-once delivery, so repeats are expected and must be deduplicated"),
                        List.of())));
    }

    protected void detectOrderLevelIssues(EvidencePack pack,
                                          Map<UUID, List<EvidencePack.PaymentView>> paymentsByOrder,
                                          List<FindingCandidate> findings) {
        for (EvidencePack.OrderView order : pack.orders()) {
            Optional<EvidencePack.ObservationView> provider = MethodSupport.bestProvider(pack, ResourceType.ORDER, order.orderId());

            provider.map(o -> BaselineOneMethod.parseOrder(o.state())).ifPresent(observedState -> {
                if (observedState != order.internalState()) {
                    findings.add(CandidateFactory.of(
                            FindingType.STATUS_MISMATCH, CertaintyLevel.LIKELY,
                            order.orderId(), null, null, null, order.orderId().toString(),
                            "Order " + order.reference() + " internal state " + order.internalState()
                                    + " disagrees with provider-authoritative state " + observedState,
                            List.of("provider-authoritative order state = " + observedState,
                                    "internal order state = " + order.internalState()),
                            List.of()));
                }
            });

            provider.map(EvidencePack.ObservationView::amountMinor).ifPresent(observedAmount -> {
                if (observedAmount != null && observedAmount != order.expectedAmountMinor()) {
                    findings.add(CandidateFactory.of(
                            FindingType.AMOUNT_MISMATCH, CertaintyLevel.LIKELY,
                            order.orderId(), null, null, null, order.orderId().toString(),
                            "Order " + order.reference() + " expected " + order.expectedAmountMinor()
                                    + " minor units but the provider reports " + observedAmount,
                            List.of("provider-authoritative amount = " + observedAmount,
                                    "internal expected amount = " + order.expectedAmountMinor()),
                            List.of()));
                }
            });

            provider.map(EvidencePack.ObservationView::currency).ifPresent(observedCurrency -> {
                if (observedCurrency != null && !observedCurrency.equalsIgnoreCase(order.currency())) {
                    findings.add(CandidateFactory.of(
                            FindingType.CURRENCY_MISMATCH, CertaintyLevel.LIKELY,
                            order.orderId(), null, null, null, order.orderId().toString(),
                            "Order " + order.reference() + " currency " + order.currency()
                                    + " disagrees with the provider currency " + observedCurrency,
                            List.of("provider-authoritative currency = " + observedCurrency,
                                    "internal currency = " + order.currency()),
                            List.of()));
                }
            });

            boolean hasPayment = paymentsByOrder.containsKey(order.orderId())
                    && !paymentsByOrder.get(order.orderId()).isEmpty();
            boolean aging = MethodSupport.olderThan(order.createdAt(), pack.evaluatedAt(), pack.stalenessThreshold());
            if (!hasPayment && order.providerState() != null && aging
                    && (order.internalState() == OrderState.CREATED || order.internalState() == OrderState.ATTEMPTED)) {
                findings.add(CandidateFactory.of(
                        FindingType.INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT, CertaintyLevel.LIKELY,
                        order.orderId(), null, null, null, order.orderId().toString(),
                        "Order " + order.reference() + " is still open after the staleness threshold and no payment was observed",
                        List.of("order state = " + order.internalState(),
                                "order created at = " + order.createdAt(),
                                "no provider payment is linked to this order"),
                        List.of("an authoritative provider payment lookup for the order")));
            }
        }
    }

    protected void detectPaymentLevelIssues(EvidencePack pack, List<FindingCandidate> findings) {
        for (EvidencePack.PaymentView payment : pack.payments()) {
            Optional<EvidencePack.ObservationView> provider = MethodSupport.bestProvider(pack, ResourceType.PAYMENT, payment.paymentId());
            if (provider.isEmpty()) {
                continue;
            }
            EvidencePack.ObservationView observed = provider.get();
            PaymentState observedState = BaselineOneMethod.parsePayment(observed.state());
            if (observedState != null && observedState != payment.internalState()) {
                findings.add(CandidateFactory.of(
                        FindingType.STATUS_MISMATCH, CertaintyLevel.LIKELY,
                        payment.orderId(), payment.paymentId(), null, null, payment.paymentId().toString(),
                        "Payment internal state " + payment.internalState()
                                + " disagrees with provider-authoritative state " + observedState,
                        List.of("provider-authoritative payment state = " + observedState,
                                "internal payment state = " + payment.internalState()),
                        List.of()));
            }
            if (observed.amountMinor() != null && observed.amountMinor() != payment.amountMinor()) {
                findings.add(CandidateFactory.of(
                        FindingType.AMOUNT_MISMATCH, CertaintyLevel.LIKELY,
                        payment.orderId(), payment.paymentId(), null, null, payment.paymentId().toString(),
                        "Payment amount " + payment.amountMinor()
                                + " disagrees with the provider amount " + observed.amountMinor(),
                        List.of("provider-authoritative amount = " + observed.amountMinor(),
                                "internal payment amount = " + payment.amountMinor()),
                        List.of()));
            }
        }
    }

    protected void detectOrphanPayments(EvidencePack pack, List<FindingCandidate> findings) {
        for (EvidencePack.ObservationView orphan : MethodSupport.orphanPaymentObservations(pack)) {
            findings.add(CandidateFactory.of(
                    FindingType.PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER, CertaintyLevel.LIKELY,
                    null, null, null, null, orphan.providerResourceId(),
                    "Provider payment " + orphan.providerResourceId() + " matches no internal order",
                    List.of("observed provider payment = " + orphan.providerResourceId(),
                            "observed state = " + orphan.state(),
                            "evidence source = " + orphan.source()),
                    List.of("the internal order this payment was intended for")));
        }
    }

    protected void detectRefundIssues(EvidencePack pack, List<FindingCandidate> findings) {
        Map<UUID, List<EvidencePack.RefundView>> refundsByPayment = MethodSupport.refundsByPayment(pack);
        for (EvidencePack.PaymentView payment : pack.payments()) {
            List<EvidencePack.RefundView> refunds = refundsByPayment.getOrDefault(payment.paymentId(), List.of());
            if (refunds.isEmpty()) {
                continue;
            }
            long refunded = MethodSupport.processedRefundTotal(refunds);
            boolean providerSaysRefunded = payment.providerState() == PaymentState.REFUNDED
                    || payment.providerState() == PaymentState.PARTIALLY_REFUNDED
                    || payment.internalState() == PaymentState.REFUNDED
                    || payment.internalState() == PaymentState.PARTIALLY_REFUNDED;
            if (providerSaysRefunded && refunded != payment.amountMinor()) {
                findings.add(CandidateFactory.of(
                        FindingType.REFUND_DISCREPANCY, CertaintyLevel.LIKELY,
                        payment.orderId(), payment.paymentId(), refunds.get(0).refundId(), null,
                        payment.paymentId().toString(),
                        "Payment is marked refunded but refund records total " + refunded
                                + " of " + payment.amountMinor() + " minor units",
                        List.of("refund records total = " + refunded,
                                "payment amount = " + payment.amountMinor(),
                                "payment state = " + payment.internalState()),
                        List.of()));
            }
            boolean anyFailed = refunds.stream().anyMatch(r -> r.state() == RefundState.FAILED);
            if (anyFailed && providerSaysRefunded) {
                findings.add(CandidateFactory.of(
                        FindingType.REFUND_DISCREPANCY, CertaintyLevel.LIKELY,
                        payment.orderId(), payment.paymentId(), refunds.get(0).refundId(), null,
                        payment.paymentId().toString() + ":failed",
                        "A failed refund is recorded while the payment is already marked refunded",
                        List.of("at least one refund record is FAILED",
                                "payment state = " + payment.internalState()),
                        List.of()));
            }
        }
    }
}
