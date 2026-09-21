package com.aegisx.transaction.service;

import com.aegisx.common.exception.IllegalTransactionStateException;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic State Machine governing all lifecycle transitions for Aegis-X transactions.
 * No transaction status change may occur outside of this authority.
 */
@Slf4j
@Service
public class TransactionStateMachine {

    private static final Map<TransactionStatus, Set<TransactionStatus>> LEGAL_TRANSITIONS;

    static {
        Map<TransactionStatus, Set<TransactionStatus>> transitions = new EnumMap<>(TransactionStatus.class);

        transitions.put(TransactionStatus.CREATED,
                EnumSet.of(TransactionStatus.PAYMENT_RECEIVED));

        transitions.put(TransactionStatus.PAYMENT_RECEIVED,
                EnumSet.of(TransactionStatus.EVIDENCE_PENDING));

        transitions.put(TransactionStatus.EVIDENCE_PENDING,
                EnumSet.of(TransactionStatus.OCR_PROCESSING));

        transitions.put(TransactionStatus.OCR_PROCESSING,
                EnumSet.of(TransactionStatus.AI_ANALYSIS));

        transitions.put(TransactionStatus.AI_ANALYSIS,
                EnumSet.of(TransactionStatus.POLICY_EVALUATION));

        transitions.put(TransactionStatus.POLICY_EVALUATION,
                EnumSet.of(TransactionStatus.APPROVED, TransactionStatus.COMPLIANCE_HOLD));

        // Terminal states in MVP — strictly immutable / irreversible
        transitions.put(TransactionStatus.APPROVED,
                Collections.emptySet());

        transitions.put(TransactionStatus.COMPLIANCE_HOLD,
                Collections.emptySet());

        LEGAL_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /**
     * Verifies if a proposed transition from currentStatus to targetStatus is legally valid.
     */
    public boolean isLegalTransition(TransactionStatus currentStatus, TransactionStatus targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        Set<TransactionStatus> allowedTargets = LEGAL_TRANSITIONS.getOrDefault(currentStatus, Collections.emptySet());
        return allowedTargets.contains(targetStatus);
    }

    /**
     * Executes a verified lifecycle transition on the given transaction with a typed TransitionActor.
     *
     * @param transaction  The target transaction entity
     * @param targetStatus The requested new status
     * @param actor        Typed system component or user role requesting the change
     * @param reason       Descriptive rationale for audit trails
     */
    public void transition(Transaction transaction, TransactionStatus targetStatus, TransitionActor actor, String reason) {
        transition(transaction, targetStatus, actor != null ? actor.name() : "SYSTEM", reason);
    }

    /**
     * Executes a verified lifecycle transition on the given transaction.
     *
     * @param transaction  The target transaction entity
     * @param targetStatus The requested new status
     * @param actor        Identity or system component requesting the change
     * @param reason       Descriptive rationale for audit trails
     */
    public void transition(Transaction transaction, TransactionStatus targetStatus, String actor, String reason) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (targetStatus == null) {
            throw new IllegalArgumentException("Target status cannot be null");
        }

        TransactionStatus currentStatus = transaction.getStatus();

        if (!isLegalTransition(currentStatus, targetStatus)) {
            log.warn("Illegal state transition attempted for transaction {} from {} to {} by actor '{}'",
                    transaction.getId(), currentStatus, targetStatus, actor);
            throw new IllegalTransactionStateException(currentStatus, targetStatus);
        }

        log.info("Transitioning transaction {} ({}) from {} to {} [Actor: '{}', Reason: '{}']",
                transaction.getId(), transaction.getReferenceNumber(), currentStatus, targetStatus, actor, reason);

        transaction.applyStatus(targetStatus);

        // TODO Phase 12: Publish audit event (actor, reason, currentStatus, targetStatus, transaction.getId())
    }

    /**
     * Convenience overload for transitions with default system actor.
     */
    public void transition(Transaction transaction, TransactionStatus targetStatus) {
        transition(transaction, targetStatus, TransitionActor.SYSTEM, "Automatic state machine transition");
    }
}

