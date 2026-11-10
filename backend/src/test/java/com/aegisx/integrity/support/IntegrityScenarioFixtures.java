package com.aegisx.integrity.support;

import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.TransferRecord;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.TransferRecordRepository;
import com.aegisx.integrity.webhook.IntegrityWebhookIngestionService;
import com.aegisx.integrity.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only fixture for integration tests that need deterministic provider outcomes.
 *
 * <p>Two properties make these integration fixtures exercise the application rather than bypass it:</p>
 * <ul>
 *   <li>Fixture webhooks are delivered through the <em>real</em> ingestion path, including raw-body
 *       HMAC verification and the database duplicate guard. Nothing bypasses production logic.</li>
 *   <li>Faults are created by changing the test provider fixture's truth or the internal record — never
 *       by editing a real provider transaction, which the specification forbids.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityScenarioFixtures {

    /** Controlled discrepancies used by deterministic integration scenarios. */
    public enum DiscrepancyKind {
        AMOUNT_MISMATCH,
        STATUS_MISMATCH,
        OUT_OF_ORDER_EVENT,
        REFUND_DISCREPANCY,
        TRANSFER_DISCREPANCY,
        STALE_PAYMENT
    }

    private final WorkspaceService workspaceService;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final TransferRecordRepository transferRepository;
    private final TestPaymentProviderAdapter simulatedAdapter;
    private final IntegrityWebhookIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public record StepResult(String step, String detail, boolean duplicate, String processingState) { }

    @Transactional
    public StepResult simulateCapturedPayment(UUID workspaceId, UUID orderId, boolean duplicateDelivery) {
        Workspace workspace = requireSimulatedWorkspace(workspaceId);
        PaymentOrder order = requireOrder(workspaceId, orderId);
        if (order.getProviderOrderId() == null) {
            throw new IntegrityConflictException("The order has no provider order id; create the provider order first");
        }

        long amount = order.getExpectedAmountMinor();
        String providerPaymentId = simulatedAdapter.registerPayment(order.getProviderOrderId(), amount,
                order.getCurrency(), PaymentState.CAPTURED);

        String body = paymentEvent("payment.captured", providerPaymentId, order.getProviderOrderId(),
                amount, order.getCurrency(), "captured");
        IntegrityWebhookIngestionService.IngestionResult first =
                deliver(workspaceId, body, "evt_sim_captured_" + providerPaymentId);

        boolean duplicateAccepted = false;
        String duplicateState = null;
        if (duplicateDelivery) {
            // A second delivery carrying the same provider event id, exactly as at-least-once
            // delivery would produce.
            IntegrityWebhookIngestionService.IngestionResult repeat =
                    deliver(workspaceId, body, "evt_sim_captured_" + providerPaymentId);
            duplicateAccepted = repeat.duplicate();
            duplicateState = repeat.state().name();
        }

        log.info("Simulated captured payment {} delivered for order {}", providerPaymentId, orderId);
        return new StepResult("payment.captured",
                "Simulated provider accepted payment " + providerPaymentId
                        + (duplicateDelivery ? " and the duplicate delivery was discarded" : ""),
                duplicateAccepted, duplicateState != null ? duplicateState : first.state().name());
    }

    @Transactional
    public StepResult injectDiscrepancy(UUID workspaceId, UUID orderId, DiscrepancyKind kind) {
        Workspace workspace = requireSimulatedWorkspace(workspaceId);
        PaymentOrder order = requireOrder(workspaceId, orderId);
        if (order.getProviderOrderId() == null) {
            throw new IntegrityConflictException("The order has no provider order id; create the provider order first");
        }

        switch (kind) {
            case AMOUNT_MISMATCH -> {
                long shortAmount = Math.max(1, order.getExpectedAmountMinor() - 5000);
                String paymentId = simulatedAdapter.registerPayment(order.getProviderOrderId(), shortAmount,
                        order.getCurrency(), PaymentState.CAPTURED);
                String body = paymentEvent("payment.captured", paymentId, order.getProviderOrderId(),
                        shortAmount, order.getCurrency(), "captured");
                deliver(workspaceId, body, "evt_sim_short_" + paymentId);
                return new StepResult(kind.name(),
                        "Provider captured " + shortAmount + " while the internal order expected "
                                + order.getExpectedAmountMinor() + " minor units", false, "PROCESSED");
            }
            case STATUS_MISMATCH -> {
                PaymentRecord payment = requireCapturedPayment(workspaceId, orderId);
                simulatedAdapter.updatePaymentState(payment.getProviderPaymentId(), PaymentState.FAILED);
                return new StepResult(kind.name(),
                        "Provider now reports FAILED for " + payment.getProviderPaymentId()
                                + " while the internal record remains " + payment.getState(), false, "PROCESSED");
            }
            case OUT_OF_ORDER_EVENT -> {
                PaymentRecord payment = requireCapturedPayment(workspaceId, orderId);
                String body = paymentEvent("payment.failed", payment.getProviderPaymentId(),
                        order.getProviderOrderId(), payment.getAmountMinor(), payment.getCurrency(), "failed");
                IntegrityWebhookIngestionService.IngestionResult result =
                        deliver(workspaceId, body, "evt_sim_late_failed_" + payment.getProviderPaymentId());
                return new StepResult(kind.name(),
                        "A payment.failed event arrived after capture was already confirmed; the terminal state was "
                                + "preserved and the contradiction recorded", false, result.state().name());
            }
            case REFUND_DISCREPANCY -> {
                PaymentRecord payment = requireCapturedPayment(workspaceId, orderId);
                String providerRefundId = simulatedAdapter.registerRefund(payment.getProviderPaymentId(),
                        payment.getAmountMinor(), payment.getCurrency(), RefundState.PROCESSED);
                refundRepository.save(RefundRecord.builder()
                        .workspaceId(workspaceId)
                        .paymentId(payment.getId())
                        .providerRefundId(providerRefundId)
                        .amountMinor(payment.getAmountMinor() / 2)
                        .currency(payment.getCurrency())
                        .providerState(RefundState.PENDING)
                        .state(RefundState.PENDING)
                        .simulated(true)
                        .lastObservedAt(Instant.now())
                        .build());
                return new StepResult(kind.name(),
                        "Internal refund " + providerRefundId + " records half the refunded amount and PENDING, the "
                                + "provider reports PROCESSED for the full amount", false, "PROCESSED");
            }
            case TRANSFER_DISCREPANCY -> {
                PaymentRecord payment = requireCapturedPayment(workspaceId, orderId);
                transferRepository.save(TransferRecord.builder()
                        .workspaceId(workspaceId)
                        .paymentId(payment.getId())
                        .orderId(order.getId())
                        .providerTransferId("trf_sim_" + UUID.randomUUID().toString().substring(0, 8))
                        .linkedAccountId("acc_sim_linked")
                        .amountMinor(payment.getAmountMinor() * 2)
                        .currency(payment.getCurrency())
                        .providerState(TransferState.CREATED)
                        .state(TransferState.CREATED)
                        .simulated(true)
                        .lastObservedAt(Instant.now())
                        .build());
                return new StepResult(kind.name(),
                        "An internal transfer record claims twice the captured payment amount and has not settled",
                        false, "PROCESSED");
            }
            case STALE_PAYMENT -> {
                PaymentRecord payment = requireCapturedPayment(workspaceId, orderId);
                paymentRepository.backdate(payment.getId(), Instant.now().minusSeconds(
                        workspace.getStalenessThresholdSeconds() + 3600));
                simulatedAdapter.updatePaymentState(payment.getProviderPaymentId(), PaymentState.AUTHORIZED);
                return new StepResult(kind.name(),
                        "The provider reports " + payment.getProviderPaymentId() + " as AUTHORIZED while the internal "
                                + "record has been pending beyond the staleness threshold", false, "PROCESSED");
            }
            default -> throw new IntegrityConflictException("Unsupported discrepancy kind " + kind);
        }
    }

    @Transactional
    public Map<String, Object> reset(UUID workspaceId) {
        requireSimulatedWorkspace(workspaceId);
        simulatedAdapter.resetControls();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", workspaceId.toString());
        detail.put("simulatedProviderControlsReset", true);
        detail.put("note", "Provider availability controls were restored; provider records remain available to match "
                + "persisted orders and payments.");
        return detail;
    }

    private IntegrityWebhookIngestionService.IngestionResult deliver(UUID workspaceId, String body, String eventId) {
        String signature = simulatedAdapter.sign(body);
        return ingestionService.ingest(workspaceId, body, signature, eventId);
    }

    private String paymentEvent(String eventType, String paymentId, String providerOrderId,
                                long amountMinor, String currency, String status) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("entity", "event");
        root.put("event", eventType);
        ObjectNode payload = root.putObject("payload");
        ObjectNode payment = payload.putObject("payment").putObject("entity");
        payment.put("id", paymentId);
        payment.put("entity", "payment");
        payment.put("amount", amountMinor);
        payment.put("currency", currency);
        payment.put("status", status);
        payment.put("order_id", providerOrderId);
        payment.put("method", "card");
        payment.put("created_at", Instant.now().getEpochSecond());
        ObjectNode order = payload.putObject("order").putObject("entity");
        order.put("id", providerOrderId);
        order.put("entity", "order");
        order.put("amount", amountMinor);
        order.put("currency", currency);
        root.put("created_at", Instant.now().getEpochSecond());
        return root.toString();
    }

    private Workspace requireSimulatedWorkspace(UUID workspaceId) {
        Workspace workspace = workspaceService.get(workspaceId);
        if (workspace.getProviderMode() != ProviderMode.SIMULATED) {
            throw new IntegrityConflictException(
                    "This workspace is bound to the Razorpay Test adapter. Faults are only injected through the "
                            + "simulated provider, because altering real provider transactions is not permitted.");
        }
        return workspace;
    }

    private PaymentOrder requireOrder(UUID workspaceId, UUID orderId) {
        return orderRepository.findById(orderId)
                .filter(candidate -> candidate.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new IntegrityNotFoundException("Order " + orderId + " does not exist in this workspace"));
    }

    private PaymentRecord requireCapturedPayment(UUID workspaceId, UUID orderId) {
        return paymentRepository.findByOrderId(orderId).stream()
                .filter(payment -> payment.getState().isConfirmedSuccess()
                        || payment.getProviderState() == PaymentState.CAPTURED)
                .findFirst()
                .orElseThrow(() -> new IntegrityConflictException(
                        "No captured payment exists for this order yet; simulate a successful payment first"));
    }

    /** Exposed so the dashboard can remind the operator that this workspace is simulated. */
    public boolean isSimulated(UUID workspaceId) {
        return workspaceService.get(workspaceId).getProviderMode() == ProviderMode.SIMULATED;
    }

    /** Convenience for tests and fixtures. */
    public OrderState currentOrderState(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(PaymentOrder::getState)
                .orElseThrow(() -> new IntegrityNotFoundException("Order " + orderId + " does not exist"));
    }
}
