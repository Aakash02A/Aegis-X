package com.aegisx.integrity;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.domain.WebhookProcessingState;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.StateTransitionRecord;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.support.IntegrityScenarioFixtures;
import com.aegisx.integrity.state.TransitionDecision;
import com.aegisx.integrity.support.IntegrityTestSupport;
import com.aegisx.integrity.webhook.IntegrityWebhookIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module C acceptance tests: webhook authenticity, duplicate safety, ordering and timeout handling.
 *
 * <p>These cover acceptance criteria 4, 5, 6 and 12 of the specification. Every assertion is made
 * against persisted rows rather than return values alone, because the property under test is
 * "no duplicate financial effect", which is only observable in the database.</p>
 */
@DisplayName("Webhook ingestion: authenticity, duplicates, ordering, timeouts")
class IntegrityWebhookIngestionTest extends IntegrityTestSupport {

    @Test
    @DisplayName("rejects a delivery whose signature does not match the raw body")
    void rejectsInvalidSignature() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 50_000);
        String body = paymentEventBody("payment.captured", "pay_SIM_FORGED", order.getProviderOrderId(),
                50_000, "INR", "captured");

        assertThatThrownBy(() -> ingestionService.ingest(workspace.getId(), body, "deadbeef", "evt_forged"))
                .isInstanceOf(InvalidWebhookSignatureException.class);

        assertThat(webhookEventRepository.countByWorkspaceId(workspace.getId()))
                .as("an unverified delivery must not be persisted as a received event")
                .isZero();
        assertThat(deliveryRepository.countByWorkspaceId(workspace.getId()))
                .as("the delivery log must not record an unverified delivery")
                .isZero();
        assertThat(paymentRepository.countByWorkspaceId(workspace.getId()))
                .as("an unverified delivery must have no business effect")
                .isZero();
    }

    @Test
    @DisplayName("applies a correctly signed first delivery")
    void appliesValidSignature() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 60_000);
        String paymentId = registerProviderPayment(order, 60_000, PaymentState.CAPTURED);

        IntegrityWebhookIngestionService.IngestionResult result = deliver(workspace,
                paymentEventBody("payment.captured", paymentId, order.getProviderOrderId(), 60_000, "INR", "captured"),
                "evt_valid_" + paymentId);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.applied()).isTrue();
        assertThat(result.state()).isEqualTo(WebhookProcessingState.PROCESSED);
        assertThat(paymentRepository.countByWorkspaceId(workspace.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeated delivery of the same event id has no second business effect")
    void duplicateDeliveryHasNoSecondEffect() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 75_000);

        IntegrityScenarioFixtures.StepResult step =
                simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), true);

        assertThat(step.duplicate()).as("the second delivery must be recognised as a duplicate").isTrue();
        assertThat(webhookEventRepository.countByWorkspaceId(workspace.getId()))
                .as("one event id must produce exactly one webhook_events row")
                .isEqualTo(1);
        assertThat(deliveryRepository.countByWorkspaceId(workspace.getId()))
                .as("both HTTP deliveries are logged")
                .isEqualTo(2);
        assertThat(deliveryRepository.countByWorkspaceIdAndFirstDeliveryFalse(workspace.getId()))
                .as("exactly one delivery is recorded as a repeat")
                .isEqualTo(1);
        assertThat(paymentRepository.countByWorkspaceId(workspace.getId()))
                .as("a duplicate must not create a second payment record")
                .isEqualTo(1);
        assertThat(refundRepository.countByWorkspaceId(workspace.getId()))
                .as("a duplicate must not create a refund")
                .isZero();
        assertThat(transferRepository.countByWorkspaceId(workspace.getId()))
                .as("a duplicate must not create a transfer")
                .isZero();

        long appliedCaptures = transitionRepository.findAllByWorkspaceIdOrderByOccurredAtDesc(workspace.getId())
                .stream()
                .filter(transition -> "CAPTURED".equals(transition.getToState()))
                .count();
        assertThat(appliedCaptures)
                .as("the capture transition must be applied exactly once")
                .isEqualTo(1);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getState().name()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("one provider event id can be consumed by each workspace bound to that provider account")
    void sameEventIdIsScopedPerWorkspace() {
        // Provider event ids are allocated per provider account, not per workspace (Razorpay's
        // x-razorpay-event-id is unique account-wide). The duplicate guard used to be
        // (provider, provider_event_id), so the second workspace's delivery collided with the first
        // workspace's row, was acknowledged as a duplicate and was never applied. Acknowledging an
        // event the provider will not redeliver loses it silently.
        Workspace first = newWorkspace();
        Workspace second = newWorkspace();
        PaymentOrder firstOrder = newOrderWithProviderOrder(first, 40_000);
        PaymentOrder secondOrder = newOrderWithProviderOrder(second, 40_000);
        String firstPayment = registerProviderPayment(firstOrder, 40_000, PaymentState.CAPTURED);
        String secondPayment = registerProviderPayment(secondOrder, 40_000, PaymentState.CAPTURED);
        String sharedEventId = "evt_shared_" + java.util.UUID.randomUUID();

        IntegrityWebhookIngestionService.IngestionResult firstResult = deliver(first,
                paymentEventBody("payment.captured", firstPayment, firstOrder.getProviderOrderId(),
                        40_000, "INR", "captured"),
                sharedEventId);
        IntegrityWebhookIngestionService.IngestionResult secondResult = deliver(second,
                paymentEventBody("payment.captured", secondPayment, secondOrder.getProviderOrderId(),
                        40_000, "INR", "captured"),
                sharedEventId);

        assertThat(firstResult.duplicate()).isFalse();
        assertThat(secondResult.duplicate())
                .as("the second workspace's first delivery is not a duplicate of another workspace's row")
                .isFalse();
        assertThat(secondResult.applied())
                .as("an event acknowledged to the provider must have been applied")
                .isTrue();
        assertThat(webhookEventRepository.countByWorkspaceId(first.getId())).isEqualTo(1);
        assertThat(webhookEventRepository.countByWorkspaceId(second.getId())).isEqualTo(1);
        assertThat(paymentRepository.countByWorkspaceId(first.getId())).isEqualTo(1);
        assertThat(paymentRepository.countByWorkspaceId(second.getId()))
                .as("the second workspace must record its own payment")
                .isEqualTo(1);

        // And a repeat to the same workspace is still rejected by the database.
        IntegrityWebhookIngestionService.IngestionResult repeat = deliver(first,
                paymentEventBody("payment.captured", firstPayment, firstOrder.getProviderOrderId(),
                        40_000, "INR", "captured"),
                sharedEventId);
        assertThat(repeat.duplicate()).as("workspace-scoped replay protection still works").isTrue();
        assertThat(webhookEventRepository.countByWorkspaceId(first.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a late failure event does not overwrite a provider-confirmed capture")
    void lateFailureDoesNotOverwriteCapture() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 90_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        IntegrityScenarioFixtures.StepResult late = simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.OUT_OF_ORDER_EVENT);

        assertThat(late.processingState())
                .as("the out-of-order event is recorded but deliberately not applied")
                .isEqualTo(WebhookProcessingState.IGNORED_TERMINAL.name());

        PaymentRecord payment = paymentRepository.findByOrderId(order.getId()).get(0);
        assertThat(payment.getState())
                .as("the canonical state must stay at the provider-confirmed outcome")
                .isEqualTo(PaymentState.CAPTURED);
        assertThat(transitionRepository.countByWorkspaceIdAndConflictTrue(workspace.getId()))
                .as("the contradiction must be visible to an operator")
                .isPositive();

        StateTransitionRecord conflict = transitionRepository
                .findByWorkspaceIdAndConflictTrueOrderByOccurredAtDesc(workspace.getId()).stream()
                .findFirst()
                .orElseThrow();
        assertThat(conflict.getReason())
                .as("the audit trail must name the state that was observed, not just the state that survived")
                .contains("FAILED")
                .contains("CAPTURED")
                .contains("not applied");
        assertThat(observationRepository.countByWorkspaceId(workspace.getId()))
                .as("the contradicted observation is still recorded as evidence")
                .isPositive();
    }

    @Test
    @DisplayName("a delayed event is still applied when it is a legal successor")
    void delayedEventIsAppliedWhenLegal() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 120_000);
        String paymentId = registerProviderPayment(order, 120_000, PaymentState.AUTHORIZED);

        // The provider has already authorised the payment, but no delivery has arrived yet. The
        // authorisation event lands late and must still be applied: it is a legal successor.
        deliver(workspace,
                paymentEventBody("payment.authorized", paymentId, order.getProviderOrderId(), 120_000, "INR", "authorized"),
                "evt_delayed_authorized_" + paymentId);

        PaymentRecord payment = paymentRepository
                .findByWorkspaceIdAndProviderPaymentId(workspace.getId(), paymentId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.AUTHORIZED);

        // The capture delivery then arrives and is applied on top of the delayed one.
        deliver(workspace,
                paymentEventBody("payment.captured", paymentId, order.getProviderOrderId(), 120_000, "INR", "captured"),
                "evt_delayed_captured_" + paymentId);

        payment = paymentRepository
                .findByWorkspaceIdAndProviderPaymentId(workspace.getId(), paymentId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(transitionRepository.countByWorkspaceIdAndConflictTrue(workspace.getId()))
                .as("an in-order pair of deliveries is not a conflict")
                .isZero();
    }

    @Test
    @DisplayName("an internal provider timeout is recorded as unknown, not as a failure")
    void timeoutIsRecordedAsUnknown() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 15_000);
        String paymentId = registerProviderPayment(order, 15_000, PaymentState.AUTHORIZED);
        deliver(workspace,
                paymentEventBody("payment.authorized", paymentId, order.getProviderOrderId(), 15_000, "INR", "authorized"),
                "evt_timeout_seed_" + paymentId);

        PaymentRecord payment = paymentRepository
                .findByWorkspaceIdAndProviderPaymentId(workspace.getId(), paymentId).orElseThrow();

        TransitionDecision timedOut = observationService.markPaymentTimeoutUnknown(payment, "TEST");
        paymentRepository.save(payment);

        assertThat(timedOut.applied()).isTrue();
        assertThat(payment.getState())
                .as("an unknown outcome must never be recorded as a provider-confirmed failure")
                .isEqualTo(PaymentState.TIMEOUT_UNKNOWN);
        assertThat(payment.getState()).isNotEqualTo(PaymentState.FAILED);

        // Only provider evidence resolves it, and it can resolve either way.
        TransitionDecision resolved = observationService.applyPaymentObservation(
                payment, PaymentState.CAPTURED, EvidenceSource.PROVIDER_API_CONFIRMED, "TEST");
        paymentRepository.save(payment);

        assertThat(resolved.applied()).isTrue();
        assertThat(payment.getState()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    @DisplayName("ingestion never creates a refund or a transfer, whatever the event says")
    void ingestionCreatesNoFinancialSideEffects() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 40_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), true);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.OUT_OF_ORDER_EVENT);

        assertThat(refundRepository.countByWorkspaceId(workspace.getId())).isZero();
        assertThat(transferRepository.countByWorkspaceId(workspace.getId())).isZero();
        assertThat(transferRepository.findByPaymentId(
                paymentRepository.findByOrderId(order.getId()).get(0).getId()))
                .noneMatch(transfer -> transfer.getState() == TransferState.PROCESSED);
    }
}
