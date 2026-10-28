package com.aegisx.integrity.service;

import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.ProviderObservation;
import com.aegisx.integrity.entity.RefundRecord;
import com.aegisx.integrity.entity.StateTransitionRecord;
import com.aegisx.integrity.entity.TransferRecord;
import com.aegisx.integrity.repository.ProviderObservationRepository;
import com.aegisx.integrity.repository.StateTransitionRepository;
import com.aegisx.integrity.state.PaymentLifecycleStateMachine;
import com.aegisx.integrity.state.TransitionDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records observations and applies them through the state machine.
 *
 * <p>Every accepted or rejected transition is persisted, and every decision is persisted whether or
 * not it changed the state. That is what makes the proposed method explainable: the reason a state
 * is what it is can be read back from the transition history.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationService {

    private final ProviderObservationRepository observationRepository;
    private final StateTransitionRepository transitionRepository;
    private final PaymentLifecycleStateMachine stateMachine;

    /** Stores a raw observation. Never mutates an existing row. */
    @Transactional
    public ProviderObservation recordObservation(UUID workspaceId,
                                                 ResourceType resourceType,
                                                 UUID entityId,
                                                 String providerResourceId,
                                                 EvidenceSource source,
                                                 String state,
                                                 Long amountMinor,
                                                 String currency,
                                                 String payload,
                                                 Instant observedAt,
                                                 boolean simulated) {
        ProviderObservation observation = ProviderObservation.builder()
                .workspaceId(workspaceId)
                .orderId(resourceType == ResourceType.ORDER ? entityId : null)
                .paymentId(resourceType == ResourceType.PAYMENT ? entityId : null)
                .refundId(resourceType == ResourceType.REFUND ? entityId : null)
                .transferId(resourceType == ResourceType.TRANSFER ? entityId : null)
                .resourceType(resourceType)
                .providerResourceId(providerResourceId == null ? "unmatched" : providerResourceId)
                .source(source)
                .evidenceRank(source.rank())
                .observedState(state == null ? "UNKNOWN" : state)
                .observedAmountMinor(amountMinor)
                .observedCurrency(currency)
                .payload(payload)
                .observedAt(observedAt == null ? Instant.now() : observedAt)
                .simulated(simulated)
                .build();
        return observationRepository.save(observation);
    }

    /** Convenience for an observation of a resource Aegis-X has no record of. */
    @Transactional
    public ProviderObservation recordOrphanObservation(UUID workspaceId, ResourceType resourceType,
                                                      String providerResourceId, EvidenceSource source,
                                                      String state, Long amountMinor, String currency,
                                                      Instant observedAt, boolean simulated) {
        return recordObservation(workspaceId, resourceType, null, providerResourceId, source, state,
                amountMinor, currency, null, observedAt, simulated);
    }

    // ----------------------------------------------------------------- orders

    @Transactional
    public TransitionDecision applyOrderObservation(PaymentOrder order, OrderState observedState,
                                                    EvidenceSource source, String actor) {
        TransitionDecision decision = stateMachine.applyOrderState(order.getState(), observedState, source);
        persist(decision, order.getWorkspaceId(), ResourceType.ORDER, order.getId(), actor);
        if (decision.applied()) {
            order.setState(OrderState.valueOf(decision.newState()));
        }
        if (source.isProviderAuthoritative()) {
            order.setProviderState(observedState);
            order.setLastObservedAt(Instant.now());
        }
        return decision;
    }

    // --------------------------------------------------------------- payments

    @Transactional
    public TransitionDecision applyPaymentObservation(PaymentRecord payment, PaymentState observedState,
                                                      EvidenceSource source, String actor) {
        TransitionDecision decision = stateMachine.applyPaymentState(payment.getState(), observedState, source);
        persist(decision, payment.getWorkspaceId(), ResourceType.PAYMENT, payment.getId(), actor);
        if (decision.applied()) {
            payment.setState(PaymentState.valueOf(decision.newState()));
            payment.setStateEvidenceRank(source.rank());
        }
        if (source.isProviderAuthoritative()) {
            payment.setProviderState(observedState);
            payment.setLastObservedAt(Instant.now());
            if (observedState == PaymentState.FAILED) {
                payment.setFailureReason("provider reported failure");
            }
        }
        return decision;
    }

    /** Records that an internal provider call timed out, leaving the outcome unknown. */
    @Transactional
    public TransitionDecision markPaymentTimeoutUnknown(PaymentRecord payment, String actor) {
        TransitionDecision decision = stateMachine.markTimeoutUnknown(payment.getState());
        persist(decision, payment.getWorkspaceId(), ResourceType.PAYMENT, payment.getId(), actor);
        if (decision.applied()) {
            payment.setState(PaymentState.TIMEOUT_UNKNOWN);
            payment.setStateEvidenceRank(EvidenceSource.INTERNAL_RECORD.rank());
        }
        return decision;
    }

    // ---------------------------------------------------------------- refunds

    @Transactional
    public TransitionDecision applyRefundObservation(RefundRecord refund, RefundState observedState,
                                                     EvidenceSource source, String actor) {
        TransitionDecision decision = stateMachine.applyRefundState(refund.getState(), observedState, source);
        persist(decision, refund.getWorkspaceId(), ResourceType.REFUND, refund.getId(), actor);
        if (decision.applied()) {
            refund.setState(RefundState.valueOf(decision.newState()));
        }
        if (source.isProviderAuthoritative()) {
            refund.setProviderState(observedState);
            refund.setLastObservedAt(Instant.now());
        }
        return decision;
    }

    // -------------------------------------------------------------- transfers

    @Transactional
    public TransitionDecision applyTransferObservation(TransferRecord transfer, TransferState observedState,
                                                       EvidenceSource source, String actor) {
        TransitionDecision decision = stateMachine.applyTransferState(transfer.getState(), observedState, source);
        persist(decision, transfer.getWorkspaceId(), ResourceType.TRANSFER, transfer.getId(), actor);
        if (decision.applied()) {
            transfer.setState(TransferState.valueOf(decision.newState()));
        }
        if (source.isProviderAuthoritative()) {
            transfer.setProviderState(observedState);
            transfer.setLastObservedAt(Instant.now());
        }
        return decision;
    }

    private void persist(TransitionDecision decision, UUID workspaceId, ResourceType type, UUID entityId, String actor) {
        if (decision.ignored()) {
            return;
        }
        transitionRepository.save(StateTransitionRecord.builder()
                .workspaceId(workspaceId)
                .entityType(type)
                .entityId(entityId)
                .fromState(decision.previousState())
                .toState(decision.newState())
                .evidenceSource(decision.evidenceSource())
                .evidenceRank(decision.evidenceSource().rank())
                .providerConfirmed(decision.evidenceSource().isProviderAuthoritative())
                .actor(actor == null ? "SYSTEM" : actor)
                .reason(decision.reason())
                .conflict(decision.conflict())
                .occurredAt(Instant.now())
                .build());
    }
}
