package com.aegisx.integrity.service;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.TransferRecord;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.provider.PaymentProviderAdapter;
import com.aegisx.integrity.provider.ProviderAdapterRegistry;
import com.aegisx.integrity.provider.ProviderOutcome;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.TransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fetches the provider's current state and records it as ranked observations.
 *
 * <p>This is the mechanism that stops a webhook from being the only source of truth. A webhook that
 * was never delivered, or that arrived out of order, is corrected here because the provider's own
 * resource endpoint is the highest-ranked evidence available.</p>
 *
 * <p>An unreachable provider returns {@code false} and records nothing. It never downgrades a
 * finding into a false absence.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderSyncService {

    private final ProviderAdapterRegistry adapterRegistry;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final TransferRecordRepository transferRepository;
    private final ObservationService observationService;
    private final IntegrityAuditService auditService;

    /** Result summary, useful for the reconciliation run notes. */
    public record SyncSummary(boolean providerReachable, int ordersSynced, int paymentsSynced,
                              int refundsSynced, int transfersSynced, List<String> notes) { }

    @Transactional
    public SyncSummary sync(Workspace workspace) {
        PaymentProviderAdapter adapter = adapterRegistry.forMode(workspace.getProviderMode());
        CapabilityStatus connectivity = adapter.connectivity();
        boolean reachable = connectivity == CapabilityStatus.OK || connectivity == CapabilityStatus.SIMULATED;
        if (!reachable) {
            return new SyncSummary(false, 0, 0, 0, 0,
                    List.of("Provider not reachable (" + connectivity + "); reconciliation ran on stored evidence only"));
        }

        int orders = 0;
        int payments = 0;
        int refunds = 0;
        int transfers = 0;
        List<String> notes = new java.util.ArrayList<>();

        for (PaymentOrder order : orderRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
            if (order.getProviderOrderId() == null) {
                continue;
            }
            ProviderOutcome<PaymentProviderAdapter.OrderSnapshot> outcome = adapter.fetchOrder(order.getProviderOrderId());
            if (outcome.hasValue()) {
                PaymentProviderAdapter.OrderSnapshot snapshot = outcome.value();
                EvidenceSource source = evidenceSource(outcome);
                observationService.recordObservation(workspace.getId(), ResourceType.ORDER, order.getId(),
                        snapshot.providerOrderId(), source, snapshot.state().name(),
                        snapshot.amountMinor(), snapshot.currency(), null, snapshot.observedAt(), outcome.simulated());
                observationService.applyOrderObservation(order, snapshot.state(), source, "PROVIDER_SYNC");
                orderRepository.save(order);
                orders++;
            } else if (outcome.isDefinitelyAbsent()) {
                notes.add("Provider reports no order for " + order.getProviderOrderId());
            }

            ProviderOutcome<List<PaymentProviderAdapter.PaymentSnapshot>> orderPayments =
                    adapter.fetchOrderPayments(order.getProviderOrderId());
            if (orderPayments.hasValue()) {
                for (PaymentProviderAdapter.PaymentSnapshot snapshot : orderPayments.value()) {
                    payments += syncPayment(workspace, adapter, order, snapshot, evidenceSource(orderPayments),
                            orderPayments.simulated(), notes);
                }
            }
        }

        for (PaymentRecord payment : paymentRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
            if (payment.getProviderPaymentId() == null) {
                continue;
            }
            ProviderOutcome<PaymentProviderAdapter.PaymentSnapshot> outcome = adapter.fetchPayment(payment.getProviderPaymentId());
            if (outcome.hasValue()) {
                PaymentProviderAdapter.PaymentSnapshot snapshot = outcome.value();
                EvidenceSource source = evidenceSource(outcome);
                observationService.recordObservation(workspace.getId(), ResourceType.PAYMENT, payment.getId(),
                        snapshot.providerPaymentId(), source, snapshot.state().name(),
                        snapshot.amountMinor(), snapshot.currency(), null, snapshot.observedAt(), outcome.simulated());
                observationService.applyPaymentObservation(payment, snapshot.state(), source, "PROVIDER_SYNC");
                paymentRepository.save(payment);
            }

            ProviderOutcome<List<PaymentProviderAdapter.RefundSnapshot>> providerRefunds =
                    adapter.fetchPaymentRefunds(payment.getProviderPaymentId());
            if (providerRefunds.hasValue()) {
                for (PaymentProviderAdapter.RefundSnapshot snapshot : providerRefunds.value()) {
                    refunds += syncRefund(workspace, snapshot, evidenceSource(providerRefunds),
                            providerRefunds.simulated());
                }
            }
        }

        if (adapter.transferCapability() == CapabilityStatus.OK || adapter.transferCapability() == CapabilityStatus.SIMULATED) {
            for (TransferRecord transfer : transferRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
                if (transfer.getProviderTransferId() == null) {
                    continue;
                }
                ProviderOutcome<PaymentProviderAdapter.TransferSnapshot> outcome =
                        adapter.fetchTransfer(transfer.getProviderTransferId());
                if (outcome.hasValue()) {
                    PaymentProviderAdapter.TransferSnapshot snapshot = outcome.value();
                    EvidenceSource source = evidenceSource(outcome);
                    observationService.recordObservation(workspace.getId(), ResourceType.TRANSFER, transfer.getId(),
                            snapshot.providerTransferId(), source, snapshot.state().name(),
                            snapshot.amountMinor(), snapshot.currency(), null, snapshot.observedAt(), outcome.simulated());
                    observationService.applyTransferObservation(transfer, snapshot.state(), source, "PROVIDER_SYNC");
                    transferRepository.save(transfer);
                    transfers++;
                }
            }
        } else {
            notes.add("Route capability is not enabled for this account; transfer verification suppressed");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ordersSynced", orders);
        detail.put("paymentsSynced", payments);
        detail.put("refundsSynced", refunds);
        detail.put("transfersSynced", transfers);
        detail.put("providerMode", workspace.getProviderMode().name());
        auditService.recordSystemEvent("PROVIDER_SYNC_COMPLETED", "PROVIDER", workspace.getId(), detail,
                workspace.getProviderMode().isSimulated());

        return new SyncSummary(true, orders, payments, refunds, transfers, List.copyOf(notes));
    }

    private int syncPayment(Workspace workspace, PaymentProviderAdapter adapter, PaymentOrder order,
                            PaymentProviderAdapter.PaymentSnapshot snapshot, EvidenceSource source,
                            boolean simulated, List<String> notes) {
        return paymentRepository.findByWorkspaceIdAndProviderPaymentId(workspace.getId(), snapshot.providerPaymentId())
                .map(existing -> {
                    observationService.recordObservation(workspace.getId(), ResourceType.PAYMENT, existing.getId(),
                            snapshot.providerPaymentId(), source, snapshot.state().name(),
                            snapshot.amountMinor(), snapshot.currency(), null, snapshot.observedAt(), simulated);
                    observationService.applyPaymentObservation(existing, snapshot.state(), source, "PROVIDER_SYNC");
                    paymentRepository.save(existing);
                    return 1;
                })
                .orElseGet(() -> {
                    observationService.recordOrphanObservation(workspace.getId(), ResourceType.PAYMENT,
                            snapshot.providerPaymentId(), source, snapshot.state().name(),
                            snapshot.amountMinor(), snapshot.currency(), snapshot.observedAt(), simulated);
                    notes.add("Provider payment " + snapshot.providerPaymentId()
                            + " is not linked to any internal order");
                    return 0;
                });
    }

    private int syncRefund(Workspace workspace, PaymentProviderAdapter.RefundSnapshot snapshot,
                           EvidenceSource source, boolean simulated) {
        return refundRepository.findByWorkspaceIdAndProviderRefundId(workspace.getId(), snapshot.providerRefundId())
                .map(existing -> {
                    observationService.recordObservation(workspace.getId(), ResourceType.REFUND, existing.getId(),
                            snapshot.providerRefundId(), source, snapshot.state().name(),
                            snapshot.amountMinor(), snapshot.currency(), null, snapshot.observedAt(), simulated);
                    observationService.applyRefundObservation(existing, snapshot.state(), source, "PROVIDER_SYNC");
                    refundRepository.save(existing);
                    return 1;
                })
                .orElseGet(() -> {
                    observationService.recordOrphanObservation(workspace.getId(), ResourceType.REFUND,
                            snapshot.providerRefundId(), source, snapshot.state().name(),
                            snapshot.amountMinor(), snapshot.currency(), snapshot.observedAt(), simulated);
                    return 0;
                });
    }

    /**
     * Simulated answers model provider answers, so they take the provider's rank; the
     * {@code simulated} flag on the observation row is what carries the "this is not a real
     * provider" information through to the UI, the findings and the audit trail.
     */
    private static EvidenceSource evidenceSource(ProviderOutcome<?> outcome) {
        return EvidenceSource.PROVIDER_API_CONFIRMED;
    }

    /** Records that a provider call timed out, leaving the payment outcome unknown. */
    @Transactional
    public void markUnknown(UUID paymentId, String detail) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            observationService.markPaymentTimeoutUnknown(payment, "PROVIDER_SYNC");
            paymentRepository.save(payment);
            auditService.recordSystemEvent("PROVIDER_CALL_TIMEOUT", "PROVIDER", payment.getWorkspaceId(),
                    Map.of("paymentId", paymentId.toString(), "detail", detail == null ? "" : detail),
                    payment.isSimulated());
        });
    }

}
