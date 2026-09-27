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

        // T1, T2: From CREATED
        transitions.put(TransactionStatus.CREATED,
                EnumSet.of(TransactionStatus.PAYMENT_RECEIVED, TransactionStatus.PAYMENT_FAILED));

        // T3: From PAYMENT_RECEIVED
        transitions.put(TransactionStatus.PAYMENT_RECEIVED,
                EnumSet.of(TransactionStatus.EVIDENCE_PENDING));

        // T4: From EVIDENCE_PENDING
        transitions.put(TransactionStatus.EVIDENCE_PENDING,
                EnumSet.of(TransactionStatus.OCR_PROCESSING));

        // T5, T6: From OCR_PROCESSING
        transitions.put(TransactionStatus.OCR_PROCESSING,
                EnumSet.of(TransactionStatus.AI_ANALYSIS, TransactionStatus.OCR_FAILED));

        // T7, T8: From AI_ANALYSIS
        transitions.put(TransactionStatus.AI_ANALYSIS,
                EnumSet.of(TransactionStatus.POLICY_EVALUATION, TransactionStatus.AI_ANALYSIS_FAILED));

        // T9, T10: From POLICY_EVALUATION
        transitions.put(TransactionStatus.POLICY_EVALUATION,
                EnumSet.of(TransactionStatus.APPROVED, TransactionStatus.COMPLIANCE_HOLD));

        // T11, T12: From COMPLIANCE_HOLD (Human review outcomes)
        transitions.put(TransactionStatus.COMPLIANCE_HOLD,
                EnumSet.of(TransactionStatus.APPROVED, TransactionStatus.REJECTED));

        // Settlement is permitted only after compliance approval.
        transitions.put(TransactionStatus.APPROVED,
                EnumSet.of(TransactionStatus.SETTLEMENT_PENDING));
        transitions.put(TransactionStatus.SETTLEMENT_PENDING,
                EnumSet.of(TransactionStatus.SETTLED, TransactionStatus.SETTLEMENT_FAILED));
        transitions.put(TransactionStatus.SETTLEMENT_FAILED,
                EnumSet.of(TransactionStatus.SETTLEMENT_PENDING));

        // T13: From OCR_FAILED (Recovery path)
        transitions.put(TransactionStatus.OCR_FAILED,
                EnumSet.of(TransactionStatus.EVIDENCE_PENDING));

        // T14: From AI_ANALYSIS_FAILED (Recovery path)
        transitions.put(TransactionStatus.AI_ANALYSIS_FAILED,
                EnumSet.of(TransactionStatus.EVIDENCE_PENDING));

        // Terminal states - no further transitions allowed
        transitions.put(TransactionStatus.REJECTED, Collections.emptySet());
        transitions.put(TransactionStatus.PAYMENT_FAILED, Collections.emptySet());
        transitions.put(TransactionStatus.SETTLED, Collections.emptySet());

        LEGAL_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /**
     * Verifies if a proposed transition from currentStatus to targetStatus is legally valid.
     */
    public boolean isLegalTransition(TransactionStatus currentStatus, TransactionStatus targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        
        // Enforce terminal state invariant - no transitions from terminal states
        if (currentStatus.isTerminal()) {
            log.warn("Attempted transition from terminal state {} to {}", currentStatus, targetStatus);
            return false;
        }
        
        Set<TransactionStatus> allowedTargets = LEGAL_TRANSITIONS.getOrDefault(currentStatus, Collections.emptySet());
        return allowedTargets.contains(targetStatus);
    }

    /**
     * Returns all valid next states from the current status.
     */
    public Set<TransactionStatus> getValidNextStates(TransactionStatus currentStatus) {
        if (currentStatus == null) {
            return Collections.emptySet();
        }
        return LEGAL_TRANSITIONS.getOrDefault(currentStatus, Collections.emptySet());
    }

    /**
     * Validates state machine invariants before transition.
     */
    private void validateInvariants(Transaction transaction, TransactionStatus targetStatus) {
        TransactionStatus currentStatus = transaction.getStatus();
        
        // Invariant 1: No state skipping - sequential progression required
        // This is enforced by the LEGAL_TRANSITIONS map
        
        // Invariant 2: Terminal states are irreversible
        if (currentStatus.isTerminal()) {
            throw new IllegalTransactionStateException(currentStatus, targetStatus, 
                "Terminal states are irreversible");
        }
        
        // Invariant 4: AI_ANALYSIS → APPROVED is forbidden (must go through POLICY_EVALUATION)
        if (currentStatus == TransactionStatus.AI_ANALYSIS && targetStatus == TransactionStatus.APPROVED) {
            throw new IllegalTransactionStateException(currentStatus, targetStatus,
                "Direct transition from AI_ANALYSIS to APPROVED is forbidden. Must go through POLICY_EVALUATION");
        }
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

        // Validate state machine invariants
        validateInvariants(transaction, targetStatus);

        if (!isLegalTransition(currentStatus, targetStatus)) {
            log.warn("Illegal state transition attempted for transaction {} from {} to {} by actor '{}'",
                    transaction.getId(), currentStatus, targetStatus, actor);
            throw new IllegalTransactionStateException(currentStatus, targetStatus);
        }

        log.info("Transitioning transaction {} ({}) from {} to {} [Actor: '{}', Reason: '{}']",
                transaction.getId(), transaction.getReferenceNumber(), currentStatus, targetStatus, actor, reason);

        // Apply the status change
        transaction.applyStatus(targetStatus);

        // Mark as terminal if the new status is terminal
        if (targetStatus.isTerminal()) {
            transaction.markAsTerminal();
        }

        // TODO Phase 12: Publish audit event (actor, reason, currentStatus, targetStatus, transaction.getId())
    }

    /**
     * Convenience overload for transitions with default system actor.
     */
    public void transition(Transaction transaction, TransactionStatus targetStatus) {
        transition(transaction, targetStatus, TransitionActor.SYSTEM, "Automatic state machine transition");
    }

    /**
     * Transitions transaction to payment failure state (from CREATED).
     */
    public void transitionToPaymentFailed(Transaction transaction, String reason) {
        transition(transaction, TransactionStatus.PAYMENT_FAILED, TransitionActor.RAZORPAY_GATEWAY, reason);
    }

    /**
     * Transitions transaction to OCR failure state with automatic recovery to EVIDENCE_PENDING.
     */
    public void transitionToOcrFailed(Transaction transaction, String reason) {
        transition(transaction, TransactionStatus.OCR_FAILED, TransitionActor.OCR_SERVICE, reason);
    }

    /**
     * Transitions transaction from OCR_FAILED back to EVIDENCE_PENDING for retry.
     */
    public void transitionFromOcrFailedToEvidencePending(Transaction transaction, String reason) {
        if (transaction.getStatus() != TransactionStatus.OCR_FAILED) {
            throw new IllegalTransactionStateException(transaction.getStatus(), TransactionStatus.EVIDENCE_PENDING, 
                "Can only transition to EVIDENCE_PENDING from OCR_FAILED state");
        }
        transition(transaction, TransactionStatus.EVIDENCE_PENDING, TransitionActor.SYSTEM, reason);
    }

    /**
     * Transitions transaction to AI analysis failure state.
     */
    public void transitionToAiAnalysisFailed(Transaction transaction, String reason) {
        transition(transaction, TransactionStatus.AI_ANALYSIS_FAILED, TransitionActor.AI_SERVICE, reason);
    }

    /**
     * Transitions transaction from AI_ANALYSIS_FAILED back to EVIDENCE_PENDING for retry.
     */
    public void transitionFromAiFailedToEvidencePending(Transaction transaction, String reason) {
        if (transaction.getStatus() != TransactionStatus.AI_ANALYSIS_FAILED) {
            throw new IllegalTransactionStateException(transaction.getStatus(), TransactionStatus.EVIDENCE_PENDING,
                "Can only transition to EVIDENCE_PENDING from AI_ANALYSIS_FAILED state");
        }
        transition(transaction, TransactionStatus.EVIDENCE_PENDING, TransitionActor.SYSTEM, reason);
    }

    /**
     * Transitions transaction from COMPLIANCE_HOLD to APPROVED (human approval).
     */
    public void approveFromComplianceHold(Transaction transaction, String reviewerId, String justification) {
        if (transaction.getStatus() != TransactionStatus.COMPLIANCE_HOLD) {
            throw new IllegalTransactionStateException(transaction.getStatus(), TransactionStatus.APPROVED,
                "Can only approve transactions in COMPLIANCE_HOLD state");
        }
        transition(transaction, TransactionStatus.APPROVED, "COMPLIANCE_OFFICER:" + reviewerId, 
                "Human approval: " + justification);
    }

    /**
     * Transitions transaction from COMPLIANCE_HOLD to REJECTED (human rejection).
     */
    public void rejectFromComplianceHold(Transaction transaction, String reviewerId, String justification) {
        if (transaction.getStatus() != TransactionStatus.COMPLIANCE_HOLD) {
            throw new IllegalTransactionStateException(transaction.getStatus(), TransactionStatus.REJECTED,
                "Can only reject transactions in COMPLIANCE_HOLD state");
        }
        transition(transaction, TransactionStatus.REJECTED, "COMPLIANCE_OFFICER:" + reviewerId, 
                "Human rejection: " + justification);
    }
}

