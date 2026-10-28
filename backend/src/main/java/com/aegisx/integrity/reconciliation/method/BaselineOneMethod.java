package com.aegisx.integrity.reconciliation.method;

import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.reconciliation.model.MethodSupport;
import com.aegisx.integrity.reconciliation.model.ReconciliationMethod;
import com.aegisx.integrity.reconciliation.model.SeverityRules;
import com.aegisx.integrity.domain.FindingType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <strong>Baseline 1 — direct status and amount comparison.</strong>
 *
 * <p>This is the "obvious" implementation an application reaches for first: take whatever value we
 * most recently saw for a resource and compare it with the internal record. It has no duplicate
 * protection, no notion of evidence rank, no state history, and no concept of staleness.</p>
 *
 * <p>Its expected weaknesses, which the benchmark measures rather than assumes:</p>
 * <ul>
 *   <li>The latest delivery wins, so a duplicate or a late contradictory event can flip the
 *       comparison result.</li>
 *   <li>It can only compare things it can see, so an order the provider never paid for produces no
 *       finding at all.</li>
 *   <li>It never notices that a payment has been pending too long, that two observations disagree,
 *       or that a refund/transfer is inconsistent.</li>
 * </ul>
 */
@Component
public class BaselineOneMethod implements ReconciliationMethod {

    @Override
    public ReconciliationStrategy strategy() {
        return ReconciliationStrategy.BASELINE_1_DIRECT_COMPARISON;
    }

    @Override
    public String displayName() {
        return "Baseline 1 - direct status/amount comparison";
    }

    @Override
    public List<FindingCandidate> evaluate(EvidencePack pack) {
        List<FindingCandidate> findings = new ArrayList<>();

        for (EvidencePack.OrderView order : pack.orders()) {
            Optional<EvidencePack.ObservationView> observed = pack.latestObservation(ResourceType.ORDER, order.orderId());
            OrderState observedState = observed.map(o -> parseOrder(o.state())).orElse(order.providerState());
            Long observedAmount = observed.map(EvidencePack.ObservationView::amountMinor).orElse(null);
            String observedCurrency = observed.map(EvidencePack.ObservationView::currency).orElse(null);

            if (observedState != null && observedState != order.internalState()) {
                findings.add(statusMismatch(
                        order.orderId(), null, order.orderId().toString(),
                        "Internal order " + order.reference() + " is " + order.internalState()
                                + " but the latest observed provider value is " + observedState,
                        "latest observed order state = " + observedState,
                        "internal order state = " + order.internalState()));
            }
            if (observedAmount != null && observedAmount != order.expectedAmountMinor()) {
                findings.add(amountMismatch(
                        order.orderId(), null, order.orderId().toString(),
                        "Expected " + order.expectedAmountMinor() + " " + order.currency()
                                + " minor units but the latest observed amount is " + observedAmount,
                        "latest observed amount = " + observedAmount,
                        "internal expected amount = " + order.expectedAmountMinor()));
            }
            if (observedCurrency != null && !observedCurrency.equalsIgnoreCase(order.currency())) {
                findings.add(currencyMismatch(
                        order.orderId(), null, order.orderId().toString(),
                        "Internal currency " + order.currency() + " differs from the latest observed currency " + observedCurrency,
                        "latest observed currency = " + observedCurrency,
                        "internal currency = " + order.currency()));
            }
        }

        for (EvidencePack.PaymentView payment : pack.payments()) {
            Optional<EvidencePack.ObservationView> observed = pack.latestObservation(ResourceType.PAYMENT, payment.paymentId());
            PaymentState observedState = observed.map(o -> parsePayment(o.state())).orElse(payment.providerState());
            if (observedState != null && observedState != payment.internalState()) {
                findings.add(statusMismatch(
                        payment.orderId(), payment.paymentId(), payment.paymentId().toString(),
                        "Internal payment state is " + payment.internalState()
                                + " but the latest observed provider value is " + observedState,
                        "latest observed payment state = " + observedState,
                        "internal payment state = " + payment.internalState()));
            }
            Long observedAmount = observed.map(EvidencePack.ObservationView::amountMinor).orElse(null);
            if (observedAmount != null && observedAmount != payment.amountMinor()) {
                findings.add(amountMismatch(
                        payment.orderId(), payment.paymentId(), payment.paymentId().toString(),
                        "Payment amount " + payment.amountMinor() + " differs from the latest observed amount " + observedAmount,
                        "latest observed amount = " + observedAmount,
                        "internal payment amount = " + payment.amountMinor()));
            }
        }

        for (EvidencePack.ObservationView orphan : MethodSupport.orphanPaymentObservations(pack)) {
            findings.add(new FindingCandidate(
                    FindingType.PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER,
                    SeverityRules.of(FindingType.PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER),
                    CertaintyLevel.UNCERTAIN,
                    null, null, null, null,
                    orphan.providerResourceId(),
                    "A provider payment was observed that matches no internal order",
                    List.of("observed provider payment " + orphan.providerResourceId()
                            + " with state " + orphan.state() + " from " + orphan.source()),
                    List.of("no internal record references " + orphan.providerResourceId())));
        }

        return List.copyOf(findings);
    }

    private static FindingCandidate statusMismatch(java.util.UUID orderId, java.util.UUID paymentId,
                                                   String entityKey, String summary,
                                                   String... evidence) {
        return new FindingCandidate(FindingType.STATUS_MISMATCH,
                SeverityRules.of(FindingType.STATUS_MISMATCH), CertaintyLevel.LIKELY,
                orderId, paymentId, null, null, entityKey, summary, List.of(evidence), List.of());
    }

    private static FindingCandidate amountMismatch(java.util.UUID orderId, java.util.UUID paymentId,
                                                   String entityKey, String summary,
                                                   String... evidence) {
        return new FindingCandidate(FindingType.AMOUNT_MISMATCH,
                SeverityRules.of(FindingType.AMOUNT_MISMATCH), CertaintyLevel.LIKELY,
                orderId, paymentId, null, null, entityKey, summary, List.of(evidence), List.of());
    }

    private static FindingCandidate currencyMismatch(java.util.UUID orderId, java.util.UUID paymentId,
                                                     String entityKey, String summary,
                                                     String... evidence) {
        return new FindingCandidate(FindingType.CURRENCY_MISMATCH,
                SeverityRules.of(FindingType.CURRENCY_MISMATCH), CertaintyLevel.LIKELY,
                orderId, paymentId, null, null, entityKey, summary, List.of(evidence), List.of());
    }

    static OrderState parseOrder(String state) {
        if (state == null) {
            return null;
        }
        try {
            return OrderState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static PaymentState parsePayment(String state) {
        if (state == null) {
            return null;
        }
        try {
            return PaymentState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
