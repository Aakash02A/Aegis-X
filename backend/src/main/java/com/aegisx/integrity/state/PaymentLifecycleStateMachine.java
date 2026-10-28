package com.aegisx.integrity.state;

import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.TransferState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Module D: the canonical lifecycle state machine.
 *
 * <p>No status may change anywhere in the integrity engine except through this class. It has no
 * dependencies and no side effects, so every rule below is directly unit-testable.</p>
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li><strong>Monotonicity.</strong> A terminal state is never reverted by a webhook, however
 *       late it arrives. The contradicting observation is recorded as a conflict for the operator to
 *       see, and the canonical state is preserved. This is the documented provider behaviour: for
 *       payouts, {@code processed}/{@code reversed} are final and later events must be ignored, and
 *       event order is explicitly not guaranteed.</li>
 *   <li><strong>One escape hatch.</strong> If the current state is {@code FAILED} and a live
 *       <em>provider API</em> lookup (not a webhook) reports {@code CAPTURED}, the API wins: the
 *       transition is applied <em>and</em> flagged as a conflict, because it contradicts a state we
 *       had already published. A webhook alone can never do this.</li>
 *   <li><strong>Illegal jumps are not conflicts.</strong> An observation that is neither a legal
 *       next state nor a contradiction of a terminal state is rejected outright. A non-authoritative
 *       source reporting such a jump is a data problem, not a lifecycle event.</li>
 *   <li><strong>Timeouts are not failures.</strong> An internal timeout is a distinct state
 *       ({@code TIMEOUT_UNKNOWN}) that only a provider lookup can resolve.</li>
 * </ol>
 */
@Slf4j
@Service
public class PaymentLifecycleStateMachine {

    // ------------------------------------------------------------------ orders

    public TransitionDecision applyOrderState(OrderState current, OrderState observed, EvidenceSource source) {
        Set<OrderState> allowed = current.allowedNext();
        if (observed == current) {
            return TransitionDecision.ignored(current.name(), source);
        }
        if (current.isTerminal()) {
            return terminalContradiction(current.name(), observed.name(), source, false);
        }
        if (allowed.contains(observed)) {
            return TransitionDecision.applied(current.name(), observed.name(),
                    "Order observed as " + observed + " from " + source, source);
        }
        return illegal(current.name(), observed.name(), source);
    }

    // --------------------------------------------------------------- payments

    public TransitionDecision applyPaymentState(PaymentState current, PaymentState observed, EvidenceSource source) {
        Set<PaymentState> allowed = current.allowedNext();
        if (observed == current) {
            return TransitionDecision.ignored(current.name(), source);
        }
        if (current.isTerminal()) {
            boolean providerApiSaysCaptured = current == PaymentState.FAILED
                    && observed == PaymentState.CAPTURED
                    && source == EvidenceSource.PROVIDER_API_CONFIRMED;
            return terminalContradiction(current.name(), observed.name(), source, providerApiSaysCaptured);
        }
        if (allowed.contains(observed)) {
            return TransitionDecision.applied(current.name(), observed.name(),
                    "Payment observed as " + observed + " from " + source, source);
        }
        return illegal(current.name(), observed.name(), source);
    }

    /**
     * Records that an internal request timed out, so the true outcome is unknown.
     *
     * <p>This is deliberately <em>not</em> {@code FAILED}. The only way out is a provider lookup.</p>
     */
    public TransitionDecision markTimeoutUnknown(PaymentState current) {
        if (current.isTerminal()) {
            return TransitionDecision.ignored(current.name(), EvidenceSource.INTERNAL_RECORD);
        }
        return TransitionDecision.applied(current.name(), PaymentState.TIMEOUT_UNKNOWN.name(),
                "Internal provider call did not complete; outcome unknown, provider lookup required",
                EvidenceSource.INTERNAL_RECORD);
    }

    // ---------------------------------------------------------------- refunds

    public TransitionDecision applyRefundState(RefundState current, RefundState observed, EvidenceSource source) {
        if (observed == current) {
            return TransitionDecision.ignored(current.name(), source);
        }
        if (current.isTerminal()) {
            return terminalContradiction(current.name(), observed.name(), source, false);
        }
        if (current == RefundState.PENDING) {
            return TransitionDecision.applied(current.name(), observed.name(),
                    "Refund observed as " + observed + " from " + source, source);
        }
        return illegal(current.name(), observed.name(), source);
    }

    // -------------------------------------------------------------- transfers

    public TransitionDecision applyTransferState(TransferState current, TransferState observed, EvidenceSource source) {
        if (observed == current) {
            return TransitionDecision.ignored(current.name(), source);
        }
        boolean legal = switch (current) {
            case CREATED -> observed == TransferState.PENDING
                    || observed == TransferState.PROCESSED
                    || observed == TransferState.FAILED;
            case PENDING -> observed == TransferState.PROCESSED || observed == TransferState.FAILED;
            case PROCESSED -> observed == TransferState.REVERSED;
            case FAILED, REVERSED -> false;
        };
        if (legal) {
            return TransitionDecision.applied(current.name(), observed.name(),
                    "Transfer observed as " + observed + " from " + source, source);
        }
        if (current.isTerminal()) {
            return terminalContradiction(current.name(), observed.name(), source, false);
        }
        return illegal(current.name(), observed.name(), source);
    }

    // ----------------------------------------------------------------- shared

    private TransitionDecision terminalContradiction(String current, String observed,
                                                     EvidenceSource source, boolean providerApiOverrides) {
        String reason = "Observation of " + observed + " from " + source
                + " contradicts terminal state " + current
                + (providerApiOverrides
                ? "; applied because a live provider API lookup outranks the recorded state"
                : "; recorded as a conflict and not applied");
        if (!providerApiOverrides) {
            log.warn("Terminal-state contradiction suppressed: {} -> {} from {}", current, observed, source);
        } else {
            log.warn("Terminal-state contradiction resolved by provider API: {} -> {}", current, observed);
        }
        return TransitionDecision.conflict(current, observed, providerApiOverrides, reason, source);
    }

    private TransitionDecision illegal(String current, String observed, EvidenceSource source) {
        log.debug("Illegal lifecycle jump rejected: {} -> {} from {}", current, observed, source);
        return TransitionDecision.rejected(current, observed,
                "Observation of " + observed + " is not a legal successor of " + current
                        + " and does not contradict a terminal state",
                source);
    }
}
