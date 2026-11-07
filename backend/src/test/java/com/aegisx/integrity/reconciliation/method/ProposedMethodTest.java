package com.aegisx.integrity.reconciliation.method;

import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.state.PaymentLifecycleStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for the rules the proposed method adds on top of Baseline 2.
 *
 * <p>These are built from hand-written evidence packs rather than the database or the scenario
 * generator, so each assertion isolates one rule. In particular they lock in the two properties that
 * a naive implementation gets wrong: a legitimate lifecycle progression is not a contradiction, and
 * a contradiction that fresh provider evidence has settled is no longer an exception.</p>
 */
@DisplayName("Proposed method rules")
class ProposedMethodTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID REFUND = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(15);

    private final ProposedMethod method = new ProposedMethod(new PaymentLifecycleStateMachine());

    private static Set<FindingType> detected(List<FindingCandidate> findings) {
        return findings.stream().map(FindingCandidate::type).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a normal order progression is not reported as a contradiction")
    void legitimateProgressionIsNotAConflict() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(order(OrderState.PAID)),
                List.of(),
                List.of(),
                List.of(),
                List.of(observation(ORDER, ResourceType.ORDER, OrderState.CREATED.name(), 100_000, NOW.minusSeconds(600)),
                        observation(ORDER, ResourceType.ORDER, OrderState.PAID.name(), 100_000, NOW.minusSeconds(60))),
                List.of(),
                true,
                true));

        assertThat(detected(findings)).doesNotContain(FindingType.CONFLICTING_OBSERVATIONS);
    }

    @Test
    @DisplayName("a late failure after a confirmed capture is reported as a contradiction")
    void lateFailureIsAConflict() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(),
                List.of(observation(PAYMENT, ResourceType.PAYMENT, PaymentState.CAPTURED.name(), 100_000, NOW.minusSeconds(300)),
                        observation(PAYMENT, ResourceType.PAYMENT, PaymentState.FAILED.name(), 100_000, NOW.minusSeconds(60))),
                List.of(),
                true,
                true));

        assertThat(detected(findings)).contains(FindingType.CONFLICTING_OBSERVATIONS);
    }

    @Test
    @DisplayName("observations tied on rank and timestamp do not imply a lifecycle sequence")
    void equalTimestampTiesAreDeterministic() {
        // Provider webhook observations carry the event's created_at, which has second precision.
        // Two events for one payment can therefore share a timestamp, and the verdict must not depend
        // on the order the rows happened to be read in.
        EvidencePack.ObservationView captured =
                observation(PAYMENT, ResourceType.PAYMENT, PaymentState.CAPTURED.name(), 100_000, NOW);
        EvidencePack.ObservationView failed =
                observation(PAYMENT, ResourceType.PAYMENT, PaymentState.FAILED.name(), 100_000, NOW);

        List<FindingCandidate> forward = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(),
                List.of(captured, failed),
                List.of(),
                true,
                true));
        List<FindingCandidate> reversed = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(),
                List.of(failed, captured),
                List.of(),
                true,
                true));

        assertThat(detected(forward)).isEqualTo(detected(reversed));
        assertThat(forward.stream().map(FindingCandidate::type).toList())
                .as("findings must be emitted in a stable order, not in hash or row order")
                .containsExactlyElementsOf(reversed.stream().map(FindingCandidate::type).toList());
        assertThat(detected(forward))
                .as("equal timestamps cannot establish which observation came later")
                .doesNotContain(FindingType.CONFLICTING_OBSERVATIONS);
    }

    @Test
    @DisplayName("source timestamps closer than their precision do not imply a lifecycle sequence")
    void observationsWithinSourcePrecisionDoNotInferSequence() {
        EvidencePack.ObservationView paidWebhook = new EvidencePack.ObservationView(
                ORDER, ResourceType.ORDER, "order_test", EvidenceSource.PROVIDER_WEBHOOK,
                EvidenceSource.PROVIDER_WEBHOOK.rank(), OrderState.PAID.name(), 100_000L, "INR", NOW, false);
        EvidencePack.ObservationView createdApiRead = new EvidencePack.ObservationView(
                ORDER, ResourceType.ORDER, "order_test", EvidenceSource.PROVIDER_API_CONFIRMED,
                EvidenceSource.PROVIDER_API_CONFIRMED.rank(), OrderState.CREATED.name(), 100_000L, "INR",
                NOW.plusMillis(250), false);

        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(order(OrderState.PAID)),
                List.of(),
                List.of(),
                List.of(),
                List.of(paidWebhook, createdApiRead),
                List.of(),
                true,
                true));

        assertThat(detected(findings))
                .as("a second-precision webhook and nearby API read cannot establish ordering")
                .doesNotContain(FindingType.CONFLICTING_OBSERVATIONS);
    }

    @Test
    @DisplayName("a contradiction that fresh provider evidence has settled is no longer an exception")
    void settledContradictionIsNotReported() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.FAILED)),
                List.of(),
                List.of(),
                List.of(observation(PAYMENT, ResourceType.PAYMENT, PaymentState.CAPTURED.name(), 100_000, NOW.minusSeconds(120))),
                List.of(new EvidencePack.TransitionView(ResourceType.PAYMENT, PAYMENT,
                        PaymentState.CAPTURED.name(), PaymentState.FAILED.name(), true, true,
                        NOW.minusSeconds(300), "Observation contradicted the recorded terminal state")),
                true,
                true));

        assertThat(detected(findings)).doesNotContain(FindingType.CONFLICTING_OBSERVATIONS);
    }

    @Test
    @DisplayName("a contradiction the current evidence still contradicts is reported")
    void liveContradictionIsReported() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.FAILED)),
                List.of(),
                List.of(),
                List.of(observation(PAYMENT, ResourceType.PAYMENT, PaymentState.FAILED.name(), 100_000, NOW.minusSeconds(120))),
                List.of(new EvidencePack.TransitionView(ResourceType.PAYMENT, PAYMENT,
                        PaymentState.CAPTURED.name(), PaymentState.FAILED.name(), true, true,
                        NOW.minusSeconds(300), "Observation contradicted the recorded terminal state")),
                true,
                true));

        assertThat(detected(findings)).contains(FindingType.CONFLICTING_OBSERVATIONS);
    }

    @Test
    @DisplayName("an order paid less than it asked for is an amount mismatch even when every record agrees")
    void shortSettlementIsDetected() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(order(OrderState.PAID)),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED, 95_000)),
                List.of(),
                List.of(),
                List.of(observation(PAYMENT, ResourceType.PAYMENT, PaymentState.CAPTURED.name(), 95_000, NOW.minusSeconds(60))),
                List.of(),
                true,
                true));

        assertThat(detected(findings))
                .as("the internal record and the provider agree on an amount that is simply too low")
                .contains(FindingType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("a full settlement raises no amount mismatch")
    void fullSettlementIsClean() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(order(OrderState.PAID)),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(),
                List.of(observation(PAYMENT, ResourceType.PAYMENT, PaymentState.CAPTURED.name(), 100_000, NOW.minusSeconds(60))),
                List.of(),
                true,
                true));

        assertThat(detected(findings)).doesNotContain(FindingType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("pending-payment staleness is false under and at the threshold and true just over it")
    void stalePaymentThresholdBoundaryIsStrictlyGreaterThan() {
        Instant exactBoundary = NOW.minus(THRESHOLD);

        List<FindingCandidate> justUnder = method.evaluate(pack(
                List.of(), List.of(pendingPayment(exactBoundary.plusNanos(1))), List.of(), List.of(),
                List.of(), List.of(), true, true));
        List<FindingCandidate> exactlyAt = method.evaluate(pack(
                List.of(), List.of(pendingPayment(exactBoundary)), List.of(), List.of(),
                List.of(), List.of(), true, true));
        List<FindingCandidate> justOver = method.evaluate(pack(
                List.of(), List.of(pendingPayment(exactBoundary.minusNanos(1))), List.of(), List.of(),
                List.of(), List.of(), true, true));

        assertThat(detected(justUnder)).doesNotContain(FindingType.STALE_PAYMENT);
        assertThat(detected(exactlyAt)).doesNotContain(FindingType.STALE_PAYMENT);
        assertThat(detected(justOver)).contains(FindingType.STALE_PAYMENT);
    }

    @Test
    @DisplayName("a refund of the wrong amount is a discrepancy even when both records say processed")
    void refundAmountMismatchIsDetected() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(order(OrderState.PAID)),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(new EvidencePack.RefundView(REFUND, PAYMENT, "rfnd_1", 50_000, "INR",
                        RefundState.PROCESSED, NOW.minusSeconds(60), false)),
                List.of(),
                List.of(observation(REFUND, ResourceType.REFUND, RefundState.PROCESSED.name(), 100_000, NOW.minusSeconds(60))),
                List.of(),
                true,
                true));

        assertThat(detected(findings)).contains(FindingType.REFUND_DISCREPANCY);
    }

    @Test
    @DisplayName("transfers are not treated as a discrepancy when Route is disabled for the account")
    void transferCapabilityGapIsReported() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(order(OrderState.PAID)),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(new EvidencePack.TransferView(TRANSFER, PAYMENT, ORDER, "trf_1", 100_000, "INR",
                        TransferState.PROCESSED, NOW.minusSeconds(60), false)),
                List.of(),
                List.of(),
                false,
                true));

        assertThat(detected(findings)).contains(FindingType.TRANSFER_DISCREPANCY);
    }

    @Test
    @DisplayName("an unreachable provider never turns into an absence of findings")
    void unreachableProviderIsDeclared() {
        List<FindingCandidate> findings = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.FAILED)),
                List.of(),
                List.of(),
                List.of(observation(PAYMENT, ResourceType.PAYMENT, PaymentState.FAILED.name(), 100_000, NOW.minusSeconds(60))),
                List.of(),
                true,
                false));

        assertThat(findings).isNotEmpty();
        assertThat(findings)
                .allSatisfy(finding -> assertThat(finding.missingEvidence())
                        .as("every finding must state that provider evidence was not freshly confirmed")
                        .anyMatch(entry -> entry.contains("provider was unreachable")));
    }

    // ------------------------------------------------------------------ builders

    private static EvidencePack pack(List<EvidencePack.OrderView> orders,
                                    List<EvidencePack.PaymentView> payments,
                                    List<EvidencePack.RefundView> refunds,
                                    List<EvidencePack.TransferView> transfers,
                                    List<EvidencePack.ObservationView> observations,
                                    List<EvidencePack.TransitionView> transitions,
                                    boolean transfersEnabled,
                                    boolean providerReachable) {
        return new EvidencePack(WORKSPACE, NOW, THRESHOLD, transfersEnabled, providerReachable,
                new ArrayList<>(orders), new ArrayList<>(payments), new ArrayList<>(refunds),
                new ArrayList<>(transfers), new ArrayList<>(observations), new ArrayList<>(transitions),
                List.of());
    }

    private static EvidencePack.OrderView order(OrderState state) {
        return new EvidencePack.OrderView(ORDER, "ORD-TEST01", "order_test", 100_000, "INR",
                state, state, NOW.minusSeconds(900), NOW.minusSeconds(120), false);
    }

    private static EvidencePack.PaymentView payment(PaymentState internal, PaymentState provider) {
        return payment(internal, provider, 100_000);
    }

    private static EvidencePack.PaymentView payment(PaymentState internal, PaymentState provider, long amount) {
        return new EvidencePack.PaymentView(PAYMENT, ORDER, "pay_test", amount, "INR",
                internal, provider, EvidenceSource.INTERNAL_RECORD.rank(),
                NOW.minusSeconds(600), NOW.minusSeconds(120), false);
    }

    private static EvidencePack.PaymentView pendingPayment(Instant lastObservedAt) {
        return new EvidencePack.PaymentView(PAYMENT, ORDER, "pay_test", 100_000, "INR",
                PaymentState.AUTHORIZED, PaymentState.AUTHORIZED, EvidenceSource.INTERNAL_RECORD.rank(),
                lastObservedAt, lastObservedAt, false);
    }

    @Test
    @DisplayName("observations that differ only in source and amount still order deterministically")
    void tiesBelowTheStateKeyOrderDeterministically() {
        // Same source, rank, timestamp, resource and state; only the amount differs. Before the
        // comparator carried the remaining fields it returned 0 for this pair, so a stable sort kept
        // whatever order the caller supplied and the "best" observation was decided by row order.
        EvidencePack.ObservationView higherAmount = new EvidencePack.ObservationView(
                PAYMENT, ResourceType.PAYMENT, "res_" + PAYMENT, EvidenceSource.PROVIDER_WEBHOOK,
                EvidenceSource.PROVIDER_WEBHOOK.rank(), PaymentState.CAPTURED.name(), 100_000L, "INR", NOW, false);
        EvidencePack.ObservationView lowerAmount = new EvidencePack.ObservationView(
                PAYMENT, ResourceType.PAYMENT, "res_" + PAYMENT, EvidenceSource.PROVIDER_WEBHOOK,
                EvidenceSource.PROVIDER_WEBHOOK.rank(), PaymentState.CAPTURED.name(), 90_000L, "INR", NOW, false);

        assertThat(EvidencePack.evidenceStrength().compare(higherAmount, lowerAmount)).isNotZero();
        assertThat(EvidencePack.evidenceStrength().compare(higherAmount, lowerAmount))
                .isEqualTo(-EvidencePack.evidenceStrength().compare(lowerAmount, higherAmount));
        assertThat(EvidencePack.recency().compare(higherAmount, lowerAmount)).isNotZero();

        List<FindingCandidate> forward = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(),
                List.of(higherAmount, lowerAmount),
                List.of(),
                true,
                true));
        List<FindingCandidate> reversed = method.evaluate(pack(
                List.of(),
                List.of(payment(PaymentState.CAPTURED, PaymentState.CAPTURED)),
                List.of(),
                List.of(),
                List.of(lowerAmount, higherAmount),
                List.of(),
                true,
                true));

        assertThat(detected(forward)).isEqualTo(detected(reversed));
        assertThat(forward.stream().map(FindingCandidate::type).toList())
                .containsExactlyElementsOf(reversed.stream().map(FindingCandidate::type).toList());
    }

    private static EvidencePack.ObservationView observation(UUID entityId, ResourceType type, String state,
                                                            long amount, Instant at) {
        return new EvidencePack.ObservationView(entityId, type, "res_" + entityId, EvidenceSource.PROVIDER_API_CONFIRMED,
                EvidenceSource.PROVIDER_API_CONFIRMED.rank(), state, amount, "INR", at, false);
    }
}
