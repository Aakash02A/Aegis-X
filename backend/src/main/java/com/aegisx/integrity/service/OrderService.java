package com.aegisx.integrity.service;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.ProviderObservation;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.StateTransitionRecord;
import com.aegisx.integrity.entity.TransferRecord;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.provider.PaymentProviderAdapter;
import com.aegisx.integrity.provider.ProviderAdapterRegistry;
import com.aegisx.integrity.provider.ProviderOutcome;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.ProviderObservationRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.StateTransitionRepository;
import com.aegisx.integrity.repository.TransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Module B: the payment and order registry.
 *
 * <p>Creating an order records the internal expectation first, then asks the provider to create a
 * matching order. If the provider is unavailable the internal order still exists with a
 * provider-confirmed state of {@code null}, which reconciliation reports as an open item rather
 * than silently discarding the request.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final WorkspaceService workspaceService;
    private final ProviderAdapterRegistry adapterRegistry;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final TransferRecordRepository transferRepository;
    private final ProviderObservationRepository observationRepository;
    private final StateTransitionRepository transitionRepository;
    private final ObservationService observationService;
    private final IntegrityAuditService auditService;

    public record CreationOutcome(PaymentOrder order, CapabilityStatus providerStatus, String note) { }

    public record OrderDetail(PaymentOrder order,
                              List<PaymentRecord> payments,
                              List<RefundRecord> refunds,
                              List<TransferRecord> transfers,
                              List<ProviderObservation> observations,
                              List<StateTransitionRecord> transitions) { }

    @Transactional
    public CreationOutcome create(UUID workspaceId, long amountMinor, String currency,
                                  String description, UUID actorId) {
        if (amountMinor <= 0) {
            throw new IntegrityConflictException("Amount must be a positive number of minor units");
        }
        Workspace workspace = workspaceService.get(workspaceId);
        String resolvedCurrency = (currency == null || currency.isBlank())
                ? workspace.getDefaultCurrency()
                : currency.toUpperCase(Locale.ROOT);
        if (resolvedCurrency.length() != 3) {
            throw new IntegrityConflictException("Currency must be a 3-letter ISO-4217 code");
        }

        String reference = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        PaymentOrder order = orderRepository.save(PaymentOrder.builder()
                .workspaceId(workspaceId)
                .internalReference(reference)
                .expectedAmountMinor(amountMinor)
                .currency(resolvedCurrency)
                .description(description)
                .state(OrderState.CREATED)
                .simulated(workspace.getProviderMode().isSimulated())
                .createdBy(actorId)
                .build());

        PaymentProviderAdapter adapter = adapterRegistry.forMode(workspace.getProviderMode());
        ProviderOutcome<PaymentProviderAdapter.OrderSnapshot> outcome = adapter.createOrder(
                new PaymentProviderAdapter.CreateOrderCommand(amountMinor, resolvedCurrency, reference, description));

        CapabilityStatus status = outcome.status();
        String note;
        if (outcome.hasValue()) {
            PaymentProviderAdapter.OrderSnapshot snapshot = outcome.value();
            order.setProviderOrderId(snapshot.providerOrderId());
            order.setProviderState(snapshot.state());
            order.setLastObservedAt(snapshot.observedAt());
            orderRepository.save(order);
            observationService.recordObservation(workspaceId, ResourceType.ORDER, order.getId(),
                    snapshot.providerOrderId(), EvidenceSource.PROVIDER_API_CONFIRMED, snapshot.state().name(),
                    snapshot.amountMinor(), snapshot.currency(), null, snapshot.observedAt(), outcome.simulated());
            note = outcome.simulated()
                    ? "Order created on the SIMULATED provider (no real Razorpay call was made)"
                    : "Order created on Razorpay Test Mode";
        } else {
            note = "Provider order was not created: " + (outcome.detail() == null ? status.name() : outcome.detail());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("internalReference", reference);
        detail.put("expectedAmountMinor", amountMinor);
        detail.put("currency", resolvedCurrency);
        detail.put("providerStatus", status.name());
        detail.put("providerOrderId", order.getProviderOrderId());
        auditService.record("ORDER_CREATED", "ORDER", "USER", workspaceId, detail,
                order.getId(), null, null, null, null, null, actorId, order.isSimulated());

        return new CreationOutcome(order, status, note);
    }

    @Transactional(readOnly = true)
    public List<PaymentOrder> list(UUID workspaceId) {
        return orderRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(UUID workspaceId, UUID orderId) {
        PaymentOrder order = orderRepository.findById(orderId)
                .filter(candidate -> candidate.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new IntegrityNotFoundException("Order " + orderId + " does not exist in this workspace"));
        List<PaymentRecord> payments = paymentRepository.findByOrderId(orderId);
        List<RefundRecord> refunds = payments.stream()
                .flatMap(payment -> refundRepository.findByPaymentId(payment.getId()).stream())
                .toList();
        List<TransferRecord> transfers = payments.stream()
                .flatMap(payment -> transferRepository.findByPaymentId(payment.getId()).stream())
                .toList();
        List<ProviderObservation> observations = observationRepository
                .findAllByWorkspaceIdOrderByObservedAtDesc(workspaceId).stream()
                .filter(observation -> orderId.equals(observation.getOrderId())
                        || payments.stream().anyMatch(p -> p.getId().equals(observation.getPaymentId())))
                .toList();
        List<StateTransitionRecord> transitions = transitionRepository
                .findAllByWorkspaceIdOrderByOccurredAtDesc(workspaceId).stream()
                .filter(transition -> orderId.equals(transition.getEntityId())
                        || payments.stream().anyMatch(p -> p.getId().equals(transition.getEntityId())))
                .toList();
        return new OrderDetail(order, payments, refunds, transfers, observations, transitions);
    }

    @Transactional(readOnly = true)
    public List<PaymentRecord> listPayments(UUID workspaceId) {
        return paymentRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<RefundRecord> listRefunds(UUID workspaceId) {
        return refundRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<TransferRecord> listTransfers(UUID workspaceId) {
        return transferRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }
}
