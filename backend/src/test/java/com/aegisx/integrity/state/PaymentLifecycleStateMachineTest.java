package com.aegisx.integrity.state;

import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.TransferState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Module D, the canonical lifecycle state machine.
 *
 * <p>The state machine is the component that decides whether a webhook is allowed to change a
 * recorded outcome. Its rules are pure, so they are tested directly here rather than through the
 * database: a failure in this class is a wrong lifecycle decision, not a wiring problem.</p>
 */
@DisplayName("Canonical lifecycle state machine")
class PaymentLifecycleStateMachineTest {

    private final PaymentLifecycleStateMachine stateMachine = new PaymentLifecycleStateMachine();

    @Nested
    @DisplayName("Payments: ordered events")
    class PaymentOrdering {

        @Test
        @DisplayName("applies a legal successor observed from a webhook")
        void appliesLegalSuccessor() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.CREATED, PaymentState.AUTHORIZED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isTrue();
            assertThat(decision.conflict()).isFalse();
            assertThat(decision.newState()).isEqualTo("AUTHORIZED");
        }

        @Test
        @DisplayName("treats a repeat observation of the current state as a no-op, not a conflict")
        void ignoresRestatement() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.AUTHORIZED, PaymentState.AUTHORIZED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.ignored()).isTrue();
            assertThat(decision.conflict()).isFalse();
            assertThat(decision.applied()).isFalse();
        }

        @Test
        @DisplayName("a late payment.failed never overwrites a provider-confirmed capture")
        void lateFailureDoesNotOverwriteCapture() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.CAPTURED, PaymentState.FAILED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied())
                    .as("a terminal state must not be reverted by an out-of-order webhook")
                    .isFalse();
            assertThat(decision.conflict()).isTrue();
            assertThat(decision.newState()).isEqualTo("CAPTURED");
            assertThat(decision.reason()).contains("contradicts terminal state CAPTURED");
        }

        @Test
        @DisplayName("a legal, provider-confirmed outcome still outranks a non-terminal state")
        void appliesCaptureAfterAuthorization() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.AUTHORIZED, PaymentState.CAPTURED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isTrue();
            assertThat(decision.newState()).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("rejects an impossible jump instead of recording it as a conflict")
        void rejectsImpossibleJump() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.CREATED, PaymentState.REFUNDED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.rejected()).isTrue();
            assertThat(decision.conflict()).isFalse();
            assertThat(decision.newState()).isEqualTo("CREATED");
        }
    }

    @Nested
    @DisplayName("Payments: the provider-API escape hatch")
    class ProviderApiOverride {

        @Test
        @DisplayName("a webhook can never overturn a recorded FAILED")
        void webhookCannotOverturnFailure() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.FAILED, PaymentState.CAPTURED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isFalse();
            assertThat(decision.conflict()).isTrue();
            assertThat(decision.newState()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a live provider API lookup is the single event that can overturn a recorded FAILED")
        void providerApiCanOverturnFailure() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.FAILED, PaymentState.CAPTURED, EvidenceSource.PROVIDER_API_CONFIRMED);

            assertThat(decision.applied())
                    .as("an authoritative provider lookup outranks the internally recorded terminal state")
                    .isTrue();
            assertThat(decision.conflict())
                    .as("the correction must still be visible as a conflict, because the published state contradicted it")
                    .isTrue();
            assertThat(decision.newState()).isEqualTo("CAPTURED");
        }

        @Test
        @DisplayName("a simulated source cannot use the escape hatch")
        void simulatedSourceHasNoAuthority() {
            TransitionDecision decision = stateMachine.applyPaymentState(
                    PaymentState.FAILED, PaymentState.CAPTURED, EvidenceSource.SIMULATED);

            assertThat(decision.applied()).isFalse();
            assertThat(decision.conflict()).isTrue();
        }
    }

    @Nested
    @DisplayName("Payments: timeouts are not failures")
    class Timeouts {

        @Test
        @DisplayName("an internal timeout becomes TIMEOUT_UNKNOWN, never FAILED")
        void timeoutIsNotAFailure() {
            TransitionDecision decision = stateMachine.markTimeoutUnknown(PaymentState.AUTHORIZED);

            assertThat(decision.applied()).isTrue();
            assertThat(decision.newState()).isEqualTo("TIMEOUT_UNKNOWN");
            assertThat(decision.newState()).isNotEqualTo(PaymentState.FAILED.name());
            assertThat(decision.evidenceSource()).isEqualTo(EvidenceSource.INTERNAL_RECORD);
        }

        @Test
        @DisplayName("a timeout cannot overwrite an already-confirmed outcome")
        void timeoutDoesNotOverwriteTerminalState() {
            TransitionDecision decision = stateMachine.markTimeoutUnknown(PaymentState.CAPTURED);

            assertThat(decision.applied()).isFalse();
            assertThat(decision.ignored()).isTrue();
        }

        @Test
        @DisplayName("TIMEOUT_UNKNOWN is resolvable by provider evidence in either direction")
        void timeoutIsResolvedByProviderEvidence() {
            assertThat(stateMachine.applyPaymentState(PaymentState.TIMEOUT_UNKNOWN, PaymentState.CAPTURED,
                    EvidenceSource.PROVIDER_API_CONFIRMED).applied()).isTrue();
            assertThat(stateMachine.applyPaymentState(PaymentState.TIMEOUT_UNKNOWN, PaymentState.FAILED,
                    EvidenceSource.PROVIDER_API_CONFIRMED).applied()).isTrue();
        }
    }

    @Nested
    @DisplayName("Orders")
    class Orders {

        @Test
        @DisplayName("applies the created to paid transition")
        void appliesPaid() {
            TransitionDecision decision = stateMachine.applyOrderState(
                    OrderState.CREATED, OrderState.PAID, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isTrue();
            assertThat(decision.newState()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("a stale created observation cannot revert a paid order")
        void staleObservationCannotRevertPaid() {
            TransitionDecision decision = stateMachine.applyOrderState(
                    OrderState.PAID, OrderState.CREATED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isFalse();
            assertThat(decision.conflict()).isTrue();
            assertThat(decision.newState()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("rejects payment-style states that orders do not have")
        void rejectsUnknownOrderState() {
            TransitionDecision decision = stateMachine.applyOrderState(
                    OrderState.CREATED, OrderState.EXPIRED, EvidenceSource.INFERRED);

            assertThat(decision.applied()).isTrue();
            assertThat(decision.newState()).isEqualTo("EXPIRED");
            assertThat(stateMachine.applyOrderState(OrderState.CREATED, OrderState.CANCELLED,
                    EvidenceSource.INFERRED).applied()).isTrue();
        }
    }

    @Nested
    @DisplayName("Refunds and transfers")
    class RefundsAndTransfers {

        @Test
        @DisplayName("a pending refund can be settled in either direction")
        void pendingRefundCanSettle() {
            assertThat(stateMachine.applyRefundState(RefundState.PENDING, RefundState.PROCESSED,
                    EvidenceSource.PROVIDER_WEBHOOK).applied()).isTrue();
            assertThat(stateMachine.applyRefundState(RefundState.PENDING, RefundState.FAILED,
                    EvidenceSource.PROVIDER_WEBHOOK).applied()).isTrue();
        }

        @Test
        @DisplayName("a processed refund is terminal and cannot be reverted")
        void processedRefundIsTerminal() {
            TransitionDecision decision = stateMachine.applyRefundState(
                    RefundState.PROCESSED, RefundState.FAILED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isFalse();
            assertThat(decision.conflict()).isTrue();
            assertThat(decision.newState()).isEqualTo("PROCESSED");
        }

        @Test
        @DisplayName("transfers may move created to pending to processed, and processed to reversed")
        void transferLifecycle() {
            assertThat(stateMachine.applyTransferState(TransferState.CREATED, TransferState.PENDING,
                    EvidenceSource.PROVIDER_WEBHOOK).applied()).isTrue();
            assertThat(stateMachine.applyTransferState(TransferState.PENDING, TransferState.PROCESSED,
                    EvidenceSource.PROVIDER_WEBHOOK).applied()).isTrue();
            assertThat(stateMachine.applyTransferState(TransferState.PROCESSED, TransferState.REVERSED,
                    EvidenceSource.PROVIDER_API_CONFIRMED).applied()).isTrue();
        }

        @Test
        @DisplayName("a reversed transfer cannot silently return to processed")
        void reversedTransferIsFinal() {
            TransitionDecision decision = stateMachine.applyTransferState(
                    TransferState.REVERSED, TransferState.PROCESSED, EvidenceSource.PROVIDER_WEBHOOK);

            assertThat(decision.applied()).isFalse();
            assertThat(decision.conflict()).isTrue();
        }
    }
}
