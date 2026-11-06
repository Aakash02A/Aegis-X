package com.aegisx.integrity.reconciliation.method;

import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.state.PaymentLifecycleStateMachine;
import com.aegisx.integrity.reconciliation.model.CandidateFactory;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.reconciliation.model.MethodSupport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <strong>Proposed method — evidence-based exception classification.</strong>
 *
 * <p>Deliberately implemented as <em>Baseline 2 plus</em> additional analysis, so the benchmark
 * isolates exactly what the extra evidence work buys. Baseline 2's rules are inherited verbatim;
 * this class adds six passes that a rule table alone cannot express:</p>
 *
 * <ol>
 *   <li><strong>Staleness.</strong> A payment that is still pending after the configured threshold
 *       is an exception even though nothing "mismatches".</li>
 *   <li><strong>Conflicting observations.</strong> Two provider-authoritative observations that
 *       disagree, or a transition that was recorded but suppressed because it contradicted a
 *       terminal state, are surfaced rather than hidden.</li>
 *   <li><strong>Cross-resource settlement.</strong> An order's expectation is compared with the
 *       captured payments actually attached to it, which catches an amount shortfall that every
 *       per-resource comparison agrees about.</li>
 *   <li><strong>Transfer reconciliation.</strong> Route transfers are compared to the captured
 *       payment, and a workspace whose account lacks Route is reported as a capability gap instead
 *       of as a phantom mismatch.</li>
 *   <li><strong>Provider refund cross-check.</strong> Refunds the provider reports are compared
 *       against refunds Aegis-X knows about, in both directions.</li>
 *   <li><strong>Certainty grading and honest gaps.</strong> Findings whose evidence is API-confirmed
 *       are graded {@code CERTAIN}; findings that rest on internal records alone stay
 *       {@code UNCERTAIN} and state what a provider lookup would add. When the provider was
 *       unreachable, every finding says so — an outage is never reported as an absence.</li>
 * </ol>
 */
@Component
public class ProposedMethod extends BaselineTwoMethod {

    private final PaymentLifecycleStateMachine stateMachine;

    public ProposedMethod(PaymentLifecycleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Override
    public ReconciliationStrategy strategy() {
        return ReconciliationStrategy.PROPOSED_EVIDENCE_BASED;
    }

    @Override
    public String displayName() {
        return "Proposed - evidence-based classification";
    }

    @Override
    public List<FindingCandidate> evaluate(EvidencePack pack) {
        List<FindingCandidate> inherited = new ArrayList<>(super.evaluate(pack));

        detectStalePayments(pack, inherited);
        detectConflictingObservations(pack, inherited);
        detectOrderSettlementMismatches(pack, inherited);
        detectTransferDiscrepancies(pack, inherited);
        detectProviderRefundCrossChecks(pack, inherited);
        detectUnreachableProviderGaps(pack, inherited);

        return regradeCertainty(pack, inherited);
    }

    private void detectStalePayments(EvidencePack pack, List<FindingCandidate> findings) {
        for (EvidencePack.PaymentView payment : pack.payments()) {
            if (!MethodSupport.isPending(payment.internalState())) {
                continue;
            }
            boolean terminalProviderEvidence = MethodSupport.bestProvider(pack, ResourceType.PAYMENT, payment.paymentId())
                    .map(o -> BaselineOneMethod.parsePayment(o.state()))
                    .filter(s -> s != null && s.isTerminal())
                    .isPresent();
            if (terminalProviderEvidence) {
                // The payment has actually settled; a status mismatch is the real finding, not staleness.
                continue;
            }
            Instant since = payment.lastObservedAt() != null ? payment.lastObservedAt() : payment.firstObservedAt();
            if (!MethodSupport.olderThan(since, pack.evaluatedAt(), pack.stalenessThreshold())) {
                continue;
            }
            findings.add(CandidateFactory.of(
                    FindingType.STALE_PAYMENT, CertaintyLevel.LIKELY,
                    payment.orderId(), payment.paymentId(), null, null, payment.paymentId().toString(),
                    "Payment has been " + payment.internalState() + " since " + since
                            + ", which exceeds the staleness threshold of " + pack.stalenessThreshold(),
                    List.of("internal payment state = " + payment.internalState(),
                            "last observed at = " + since,
                            "staleness threshold = " + pack.stalenessThreshold()),
                    List.of("a provider state fetch would resolve whether this is still in flight")));
        }
    }

    private void detectConflictingObservations(EvidencePack pack, List<FindingCandidate> findings) {
        Set<String> reported = new HashSet<>();

        for (EvidencePack.TransitionView transition : pack.transitions()) {
            if (!transition.conflict()) {
                continue;
            }

            // A recorded contradiction is a live exception only while the evidence still contradicts
            // it. If the best current provider-authoritative observation agrees with the state we
            // kept, the disagreement has been settled and re-reporting it would make a resolution
            // impossible to verify.
            boolean settled = MethodSupport.bestProvider(pack, transition.entityType(), transition.entityId())
                    .map(observation -> observation.state().equals(transition.fromState()))
                    .orElse(false);
            if (settled) {
                continue;
            }

            String key = transition.entityType() + "|" + transition.entityId() + "|" + transition.toState();
            if (!reported.add(key)) {
                continue;
            }
            findings.add(CandidateFactory.of(
                    FindingType.CONFLICTING_OBSERVATIONS, CertaintyLevel.LIKELY,
                    transition.entityType() == ResourceType.ORDER ? transition.entityId() : null,
                    transition.entityType() == ResourceType.PAYMENT ? transition.entityId() : null,
                    transition.entityType() == ResourceType.REFUND ? transition.entityId() : null,
                    transition.entityType() == ResourceType.TRANSFER ? transition.entityId() : null,
                    key,
                    // The narrative comes from the recorded reason rather than from fromState/toState:
                    // when a contradiction is suppressed the transition row keeps the *recorded* state
                    // in both columns, so reconstructing the story from them would read "PAID
                    // contradicted PAID". The reason always names the state that was actually observed
                    // and the source that reported it.
                    "A lifecycle contradiction was recorded but not applied, so the recorded state "
                            + transition.fromState() + " stands. " + transition.reason(),
                    List.of("recorded state = " + transition.fromState(),
                            "transition outcome recorded = " + transition.toState(),
                            "observation occurred at = " + transition.occurredAt(),
                            "explanation recorded = " + transition.reason()),
                    List.of("a live provider state fetch to settle the contradiction")));
        }

        // A TreeMap keeps the per-entity passes — and therefore the finding order — independent of
        // hash iteration order, so two runs over identical evidence emit findings in the same order.
        Map<String, List<EvidencePack.ObservationView>> byEntity = new java.util.TreeMap<>();
        for (EvidencePack.ObservationView observation : pack.observations()) {
            if (!observation.source().isProviderAuthoritative() || observation.entityId() == null) {
                continue;
            }
            byEntity.computeIfAbsent(observation.resourceType() + "|" + observation.entityId(),
                    k -> new ArrayList<>()).add(observation);
        }

        byEntity.forEach((key, observations) -> {
            List<EvidencePack.ObservationView> ordered = new ArrayList<>(observations);
            // Ties are broken deterministically: two webhook observations of one resource may share
            // the event's second-precision created_at, and "which one is the latest step" must not
            // depend on the database's row order.
            ordered.sort(java.util.Comparator.comparing(EvidencePack.ObservationView::observedAt)
                    .thenComparingInt(EvidencePack.ObservationView::rank)
                    .thenComparing(EvidencePack.ObservationView::providerResourceId,
                            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                    .thenComparing(EvidencePack.ObservationView::state,
                            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));

            // Two different values are not by themselves a contradiction. A resource legitimately
            // moves through its lifecycle, so CREATED followed by PAID is ordinary progress. Only an
            // observation that is not a legal successor of the one before it is reported, which is
            // the same rule the state machine applies when it decides a transition. Reporting every
            // change would flag every correctly completed payment as an exception.
            // Only the most recent step is judged, because that is the pair that describes the
            // current picture. An illegal step that a later, correct value superseded is history,
            // already visible in the transition history, and re-reporting it forever would make a
            // recorded resolution permanently unverifiable.
            if (ordered.size() < 2) {
                return;
            }
            EvidencePack.ObservationView previous = ordered.get(ordered.size() - 2);
            EvidencePack.ObservationView current = ordered.get(ordered.size() - 1);
            Duration separation = Duration.between(previous.observedAt(), current.observedAt());
            if (current.state().equals(previous.state())
                    || separation.compareTo(previous.source().timePrecision()) < 0
                    || separation.compareTo(current.source().timePrecision()) < 0
                    || isLegalSuccession(previous.resourceType(), previous.state(), current.state())
                    || !reported.add(key)) {
                return;
            }
            findings.add(CandidateFactory.of(
                    FindingType.CONFLICTING_OBSERVATIONS, CertaintyLevel.LIKELY,
                    current.resourceType() == ResourceType.ORDER ? current.entityId() : null,
                    current.resourceType() == ResourceType.PAYMENT ? current.entityId() : null,
                    current.resourceType() == ResourceType.REFUND ? current.entityId() : null,
                    current.resourceType() == ResourceType.TRANSFER ? current.entityId() : null,
                    key,
                    "The latest two provider-authoritative observations of the same resource disagree: "
                            + previous.state() + " at " + previous.observedAt() + " followed by "
                            + current.state() + " at " + current.observedAt()
                            + ", which is not a legal successor of it",
                    List.of("earlier observation = " + previous.state() + " from " + previous.source()
                                    + " at " + previous.observedAt(),
                            "latest observation = " + current.state() + " from " + current.source()
                                    + " at " + current.observedAt(),
                            "a legal successor of " + previous.state() + " is expected for the same resource"),
                    List.of("a fresh provider state fetch to determine which observation is current")));
        });
    }

    /** True when {@code to} is a normal next state after {@code from} for that resource kind. */
    private boolean isLegalSuccession(ResourceType type, String from, String to) {
        try {
            return switch (type) {
                case ORDER -> stateMachine.applyOrderState(OrderState.valueOf(from), OrderState.valueOf(to),
                        EvidenceSource.PROVIDER_API_CONFIRMED).applied();
                case PAYMENT -> stateMachine.applyPaymentState(PaymentState.valueOf(from), PaymentState.valueOf(to),
                        EvidenceSource.PROVIDER_API_CONFIRMED).applied();
                case REFUND -> stateMachine.applyRefundState(RefundState.valueOf(from), RefundState.valueOf(to),
                        EvidenceSource.PROVIDER_API_CONFIRMED).applied();
                case TRANSFER -> stateMachine.applyTransferState(TransferState.valueOf(from), TransferState.valueOf(to),
                        EvidenceSource.PROVIDER_API_CONFIRMED).applied();
            };
        } catch (IllegalArgumentException e) {
            // An unrecognised state value cannot be reasoned about, so it is not treated as a legal step.
            return false;
        }
    }

    /**
     * Cross-resource settlement check.
     *
     * <p>The other amount rules compare a resource with the provider's view <em>of that same
     * resource</em>. That is blind to the failure mode where the internal payment record and the
     * provider both agree on an amount that is simply less than the order asked for: every pair
     * matches, yet the money is short. This pass compares the order's expectation against the
     * captured payments actually attached to it, which is a correlation no per-resource rule can
     * express.</p>
     */
    private void detectOrderSettlementMismatches(EvidencePack pack, List<FindingCandidate> findings) {
        Map<UUID, List<EvidencePack.PaymentView>> paymentsByOrder = MethodSupport.paymentsByOrder(pack);
        for (EvidencePack.OrderView order : pack.orders()) {
            List<EvidencePack.PaymentView> payments = paymentsByOrder.getOrDefault(order.orderId(), List.of());
            if (payments.isEmpty()) {
                continue;
            }
            List<EvidencePack.PaymentView> settled = payments.stream()
                    .filter(payment -> payment.internalState().isConfirmedSuccess())
                    .toList();
            if (settled.isEmpty()) {
                continue;
            }
            long settledTotal = settled.stream().mapToLong(EvidencePack.PaymentView::amountMinor).sum();
            if (settledTotal == order.expectedAmountMinor()) {
                continue;
            }
            findings.add(CandidateFactory.of(
                    FindingType.AMOUNT_MISMATCH, CertaintyLevel.LIKELY,
                    order.orderId(), settled.get(0).paymentId(), null, null, order.orderId().toString(),
                    "Order " + order.reference() + " expected " + order.expectedAmountMinor()
                            + " " + order.currency() + " minor units, but the captured payments on it total "
                            + settledTotal,
                    List.of("internal expected amount = " + order.expectedAmountMinor(),
                            "total of captured payments for this order = " + settledTotal,
                            "captured payments considered = " + settled.size(),
                            "payments attached to the order = " + payments.size()),
                    List.of("an authoritative provider payment listing for this order, to confirm what was "
                            + "actually captured before any money is moved in either direction")));
        }
    }

    private void detectTransferDiscrepancies(EvidencePack pack, List<FindingCandidate> findings) {
        if (!pack.transfers().isEmpty() && !pack.transferCapabilityEnabled()) {
            findings.add(CandidateFactory.of(
                    FindingType.TRANSFER_DISCREPANCY, CertaintyLevel.CERTAIN,
                    null, null, null, pack.transfers().get(0).transferId(), "capability:" + pack.workspaceId(),
                    "Internal transfer records exist but the workspace reports Route as not enabled",
                    List.of("internal transfer records = " + pack.transfers().size(),
                            "workspace transfer capability = not enabled",
                            "Route enablement is an account-level product and cannot be assumed"),
                    List.of("confirmation from the provider that Route is enabled for this account")));
            return;
        }
        if (!pack.transferCapabilityEnabled()) {
            return;
        }

        Map<UUID, List<EvidencePack.TransferView>> transfersByPayment = MethodSupport.transfersByPayment(pack);
        for (Map.Entry<UUID, List<EvidencePack.TransferView>> entry : transfersByPayment.entrySet()) {
            UUID paymentId = entry.getKey();
            List<EvidencePack.TransferView> transfers = entry.getValue();
            Optional<EvidencePack.PaymentView> payment = pack.payments().stream()
                    .filter(p -> p.paymentId().equals(paymentId))
                    .findFirst();
            if (payment.isEmpty()) {
                continue;
            }
            EvidencePack.PaymentView paymentView = payment.get();
            long moved = transfers.stream().mapToLong(EvidencePack.TransferView::amountMinor).sum();

            if (!paymentView.internalState().isConfirmedSuccess()) {
                findings.add(CandidateFactory.of(
                        FindingType.TRANSFER_DISCREPANCY, CertaintyLevel.LIKELY,
                        paymentView.orderId(), paymentId, null, transfers.get(0).transferId(), paymentId.toString(),
                        "A transfer exists for a payment that is not provider-confirmed as captured",
                        List.of("payment state = " + paymentView.internalState(),
                                "transfer count = " + transfers.size(),
                                "Route requires a captured payment before a transfer is created"),
                        List.of("a provider payment state fetch to confirm capture")));
                continue;
            }
            if (moved > paymentView.amountMinor()) {
                findings.add(CandidateFactory.of(
                        FindingType.TRANSFER_DISCREPANCY, CertaintyLevel.LIKELY,
                        paymentView.orderId(), paymentId, null, transfers.get(0).transferId(), paymentId.toString(),
                        "Transfers totalling " + moved + " exceed the captured payment amount " + paymentView.amountMinor(),
                        List.of("transfer total = " + moved,
                                "captured payment amount = " + paymentView.amountMinor()),
                        List.of("a provider transfer fetch for each affected transfer")));
            }
            boolean noneProcessed = transfers.stream().noneMatch(t -> t.state().isTerminal());
            if (noneProcessed && MethodSupport.olderThan(transfers.get(0).lastObservedAt(),
                    pack.evaluatedAt(), pack.stalenessThreshold())) {
                findings.add(CandidateFactory.of(
                        FindingType.TRANSFER_DISCREPANCY, CertaintyLevel.LIKELY,
                        paymentView.orderId(), paymentId, null, transfers.get(0).transferId(), paymentId.toString(),
                        "Transfers for a captured payment have not reached a terminal state within the threshold",
                        List.of("transfer states = " + transfers.stream().map(t -> t.state().name()).toList(),
                                "first transfer observed at = " + transfers.get(0).lastObservedAt(),
                                "staleness threshold = " + pack.stalenessThreshold()),
                        List.of("a provider transfer fetch to obtain the current transfer state")));
            }
        }
    }

    private void detectProviderRefundCrossChecks(EvidencePack pack, List<FindingCandidate> findings) {
        for (EvidencePack.ObservationView orphan : pack.observations()) {
            if (orphan.resourceType() != ResourceType.REFUND || orphan.entityId() != null) {
                continue;
            }
            findings.add(CandidateFactory.of(
                    FindingType.REFUND_DISCREPANCY, CertaintyLevel.LIKELY,
                    null, null, null, null, orphan.providerResourceId(),
                    "The provider reports refund " + orphan.providerResourceId()
                            + " that Aegis-X has no internal record of",
                    List.of("provider refund = " + orphan.providerResourceId(),
                            "provider refund state = " + orphan.state(),
                            "evidence source = " + orphan.source()),
                    List.of("the internal payment this refund belongs to")));
        }

        for (EvidencePack.RefundView refund : pack.refunds()) {
            Optional<EvidencePack.ObservationView> provider = MethodSupport.bestProvider(pack, ResourceType.REFUND, refund.refundId());
            if (provider.isEmpty()) {
                continue;
            }
            EvidencePack.ObservationView observation = provider.get();
            RefundState observed = parseRefund(observation.state());
            if (observed != null && observed != refund.state()) {
                findings.add(CandidateFactory.of(
                        FindingType.REFUND_DISCREPANCY, CertaintyLevel.LIKELY,
                        null, refund.paymentId(), refund.refundId(), null, refund.refundId().toString(),
                        "Internal refund state " + refund.state()
                                + " disagrees with provider-authoritative state " + observed,
                        List.of("provider-authoritative refund state = " + observed,
                                "internal refund state = " + refund.state()),
                        List.of()));
            }

            // A refunded amount is as much a discrepancy as a refunded state. Comparing only the
            // state would let a refund of half the recorded amount reconcile cleanly once the
            // provider and the internal record agree on the word "processed".
            Long providerAmount = observation.amountMinor();
            if (providerAmount != null && providerAmount != refund.amountMinor()) {
                findings.add(CandidateFactory.of(
                        FindingType.REFUND_DISCREPANCY, CertaintyLevel.LIKELY,
                        null, refund.paymentId(), refund.refundId(), null,
                        refund.refundId().toString() + ":amount",
                        "Refund " + observation.providerResourceId() + " is recorded internally as "
                                + refund.amountMinor() + " " + refund.currency()
                                + " minor units but the provider reports " + providerAmount,
                        List.of("provider-authoritative refund amount = " + providerAmount,
                                "internal refund amount = " + refund.amountMinor(),
                                "provider refund state = " + observation.state()),
                        List.of("the provider's refund listing for the payment this refund belongs to")));
            }
        }
    }

    private void detectUnreachableProviderGaps(EvidencePack pack, List<FindingCandidate> findings) {
        if (pack.providerReachable()) {
            return;
        }
        List<FindingCandidate> enriched = new ArrayList<>();
        for (FindingCandidate candidate : findings) {
            List<String> missing = new ArrayList<>(candidate.missingEvidence());
            missing.add("the provider was unreachable during this run, so every provider-side value is "
                    + "carried forward from the last accepted observation rather than freshly confirmed");
            enriched.add(new FindingCandidate(candidate.type(), candidate.severity(),
                    candidate.certainty() == CertaintyLevel.CERTAIN ? CertaintyLevel.LIKELY : candidate.certainty(),
                    candidate.orderId(), candidate.paymentId(), candidate.refundId(), candidate.transferId(),
                    candidate.entityKey(), candidate.summary(), candidate.evidence(), missing));
        }
        findings.clear();
        findings.addAll(enriched);
    }

    private List<FindingCandidate> regradeCertainty(EvidencePack pack, List<FindingCandidate> findings) {
        List<FindingCandidate> regraded = new ArrayList<>();
        for (FindingCandidate candidate : findings) {
            CertaintyLevel certainty = candidate.certainty();
            if (hasApiConfirmedEvidence(pack, candidate)) {
                certainty = CertaintyLevel.CERTAIN;
            }
            regraded.add(new FindingCandidate(candidate.type(), candidate.severity(), certainty,
                    candidate.orderId(), candidate.paymentId(), candidate.refundId(), candidate.transferId(),
                    candidate.entityKey(), candidate.summary(), candidate.evidence(), candidate.missingEvidence()));
        }
        return List.copyOf(regraded);
    }

    private boolean hasApiConfirmedEvidence(EvidencePack pack, FindingCandidate candidate) {
        return pack.observations().stream()
                .filter(o -> o.source() == EvidenceSource.PROVIDER_API_CONFIRMED)
                .anyMatch(o -> matches(candidate, o));
    }

    private boolean matches(FindingCandidate candidate, EvidencePack.ObservationView observation) {
        if (observation.entityId() == null) {
            return observation.providerResourceId() != null
                    && observation.providerResourceId().equals(candidate.entityKey());
        }
        return observation.entityId().equals(candidate.orderId())
                || observation.entityId().equals(candidate.paymentId())
                || observation.entityId().equals(candidate.refundId())
                || observation.entityId().equals(candidate.transferId());
    }

    private static RefundState parseRefund(String state) {
        if (state == null) {
            return null;
        }
        try {
            return RefundState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Exposed so the registry can use it without duplicating the enum parsing. */
    public static PaymentState parsePaymentState(String state) {
        return BaselineOneMethod.parsePayment(state);
    }
}
