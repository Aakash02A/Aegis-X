package com.aegisx.integrity.reconciliation;

import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.ProviderObservation;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.StateTransitionRecord;
import com.aegisx.integrity.entity.TransferRecord;
import com.aegisx.integrity.entity.WebhookDelivery;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.ProviderObservationRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.StateTransitionRepository;
import com.aegisx.integrity.repository.TransferRecordRepository;
import com.aegisx.integrity.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds an {@link EvidencePack} from persisted rows.
 *
 * <p>This is the only place that translates database entities into the neutral evidence model, so
 * the production reconciliation path and the offline benchmark feed the comparators the same shape
 * of input.</p>
 */
@Component
@RequiredArgsConstructor
public class EvidencePackBuilder {

    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final TransferRecordRepository transferRepository;
    private final ProviderObservationRepository observationRepository;
    private final StateTransitionRepository transitionRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final IntegrityProperties properties;

    public EvidencePack build(Workspace workspace, boolean providerReachable) {
        UUID workspaceId = workspace.getId();
        Instant now = Instant.now();
        Duration threshold = resolveStalenessThreshold(workspace);

        List<EvidencePack.OrderView> orders = orderRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toOrder)
                .toList();
        List<EvidencePack.PaymentView> payments = paymentRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toPayment)
                .toList();
        List<EvidencePack.RefundView> refunds = refundRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toRefund)
                .toList();
        List<EvidencePack.TransferView> transfers = transferRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(this::toTransfer)
                .toList();
        List<EvidencePack.ObservationView> observations = observationRepository
                .findAllByWorkspaceIdOrderByObservedAtDesc(workspaceId).stream()
                .map(this::toObservation)
                .toList();
        List<EvidencePack.TransitionView> transitions = transitionRepository
                .findAllByWorkspaceIdOrderByOccurredAtDesc(workspaceId).stream()
                .map(this::toTransition)
                .toList();
        List<EvidencePack.WebhookObservation> deliveries = webhookDeliveryRepository
                .findAllByWorkspaceIdOrderByReceivedAtDesc(workspaceId).stream()
                .map(this::toWebhook)
                .toList();

        boolean transfersEnabled = workspace.getTransferCapability() == CapabilityStatus.OK
                || workspace.getTransferCapability() == CapabilityStatus.SIMULATED;

        return new EvidencePack(workspaceId, now, threshold, transfersEnabled, providerReachable,
                orders, payments, refunds, transfers, observations, transitions, deliveries);
    }

    private Duration resolveStalenessThreshold(Workspace workspace) {
        long seconds = workspace.getStalenessThresholdSeconds() > 0
                ? workspace.getStalenessThresholdSeconds()
                : properties.getStalenessThreshold().toSeconds();
        return Duration.ofSeconds(seconds);
    }

    private EvidencePack.OrderView toOrder(PaymentOrder order) {
        return new EvidencePack.OrderView(order.getId(), order.getInternalReference(), order.getProviderOrderId(),
                order.getExpectedAmountMinor(), order.getCurrency(), order.getState(), order.getProviderState(),
                order.getCreatedAt(), order.getLastObservedAt(), order.isSimulated());
    }

    private EvidencePack.PaymentView toPayment(PaymentRecord payment) {
        return new EvidencePack.PaymentView(payment.getId(), payment.getOrderId(), payment.getProviderPaymentId(),
                payment.getAmountMinor(), payment.getCurrency(), payment.getState(), payment.getProviderState(),
                payment.getStateEvidenceRank(), payment.getFirstObservedAt(), payment.getLastObservedAt(),
                payment.isSimulated());
    }

    private EvidencePack.RefundView toRefund(RefundRecord refund) {
        return new EvidencePack.RefundView(refund.getId(), refund.getPaymentId(), refund.getProviderRefundId(),
                refund.getAmountMinor(), refund.getCurrency(), refund.getState(), refund.getLastObservedAt(),
                refund.isSimulated());
    }

    private EvidencePack.TransferView toTransfer(TransferRecord transfer) {
        return new EvidencePack.TransferView(transfer.getId(), transfer.getPaymentId(), transfer.getOrderId(),
                transfer.getProviderTransferId(), transfer.getAmountMinor(), transfer.getCurrency(),
                transfer.getState(), transfer.getLastObservedAt(), transfer.isSimulated());
    }

    private EvidencePack.ObservationView toObservation(ProviderObservation observation) {
        UUID entityId = switch (observation.getResourceType()) {
            case ORDER -> observation.getOrderId();
            case PAYMENT -> observation.getPaymentId();
            case REFUND -> observation.getRefundId();
            case TRANSFER -> observation.getTransferId();
        };
        return new EvidencePack.ObservationView(entityId, observation.getResourceType(),
                observation.getProviderResourceId(), observation.getSource(), observation.getEvidenceRank(),
                observation.getObservedState(), observation.getObservedAmountMinor(), observation.getObservedCurrency(),
                observation.getObservedAt(), observation.isSimulated());
    }

    private EvidencePack.TransitionView toTransition(StateTransitionRecord transition) {
        return new EvidencePack.TransitionView(transition.getEntityType(), transition.getEntityId(),
                transition.getFromState(), transition.getToState(), transition.isConflict(),
                transition.isProviderConfirmed(), transition.getOccurredAt(), transition.getReason());
    }

    private EvidencePack.WebhookObservation toWebhook(WebhookDelivery delivery) {
        return new EvidencePack.WebhookObservation(delivery.getProviderEventId(), delivery.getEventType(),
                delivery.getProviderEventId(), null, delivery.getProviderCreatedAt(),
                delivery.getReceivedAt(), !delivery.isFirstDelivery());
    }
}
