package com.aegisx.transaction.service;

import com.aegisx.auth.entity.User;
import com.aegisx.common.exception.IllegalTransactionStateException;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionStateMachineTest {

    private TransactionStateMachine stateMachine;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        stateMachine = new TransactionStateMachine();
        
        User merchant = User.builder()
                .fullName("test-merchant")
                .build();
                
        transaction = Transaction.builder()
                .referenceNumber("TXN-TEST-001")
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Test Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.CREATED)
                .isTerminal(false)
                .build();
    }

    @Test
    @DisplayName("Should allow valid transitions according to TAS specification")
    void shouldAllowValidTransitions() {
        // T1: CREATED -> PAYMENT_RECEIVED
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.CREATED, TransactionStatus.PAYMENT_RECEIVED));
        
        // T2: CREATED -> PAYMENT_FAILED  
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.CREATED, TransactionStatus.PAYMENT_FAILED));
        
        // T3: PAYMENT_RECEIVED -> EVIDENCE_PENDING
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.PAYMENT_RECEIVED, TransactionStatus.EVIDENCE_PENDING));
        
        // T4: EVIDENCE_PENDING -> OCR_PROCESSING
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.EVIDENCE_PENDING, TransactionStatus.OCR_PROCESSING));
        
        // T5: OCR_PROCESSING -> AI_ANALYSIS
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.OCR_PROCESSING, TransactionStatus.AI_ANALYSIS));
        
        // T6: OCR_PROCESSING -> OCR_FAILED
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.OCR_PROCESSING, TransactionStatus.OCR_FAILED));
        
        // T7: AI_ANALYSIS -> POLICY_EVALUATION
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.AI_ANALYSIS, TransactionStatus.POLICY_EVALUATION));
        
        // T8: AI_ANALYSIS -> AI_ANALYSIS_FAILED
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.AI_ANALYSIS, TransactionStatus.AI_ANALYSIS_FAILED));
        
        // T9: POLICY_EVALUATION -> APPROVED
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.POLICY_EVALUATION, TransactionStatus.APPROVED));
        
        // T10: POLICY_EVALUATION -> COMPLIANCE_HOLD
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.POLICY_EVALUATION, TransactionStatus.COMPLIANCE_HOLD));
        
        // T11: COMPLIANCE_HOLD -> APPROVED
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.COMPLIANCE_HOLD, TransactionStatus.APPROVED));
        
        // T12: COMPLIANCE_HOLD -> REJECTED
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.COMPLIANCE_HOLD, TransactionStatus.REJECTED));
        
        // T13: OCR_FAILED -> EVIDENCE_PENDING
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.OCR_FAILED, TransactionStatus.EVIDENCE_PENDING));
        
        // T14: AI_ANALYSIS_FAILED -> EVIDENCE_PENDING
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.AI_ANALYSIS_FAILED, TransactionStatus.EVIDENCE_PENDING));
        // A failed optional AI advisory can still proceed to deterministic policy evaluation.
        assertTrue(stateMachine.isLegalTransition(TransactionStatus.AI_ANALYSIS_FAILED, TransactionStatus.POLICY_EVALUATION));
    }

    @Test
    @DisplayName("Should reject invalid transitions")
    void shouldRejectInvalidTransitions() {
        // Skip states - not allowed
        assertFalse(stateMachine.isLegalTransition(TransactionStatus.CREATED, TransactionStatus.EVIDENCE_PENDING));
        assertFalse(stateMachine.isLegalTransition(TransactionStatus.PAYMENT_RECEIVED, TransactionStatus.AI_ANALYSIS));
        
        // Backward transitions (except allowed recovery paths)
        assertFalse(stateMachine.isLegalTransition(TransactionStatus.AI_ANALYSIS, TransactionStatus.OCR_PROCESSING));
        assertFalse(stateMachine.isLegalTransition(TransactionStatus.POLICY_EVALUATION, TransactionStatus.AI_ANALYSIS));
        
        // Direct AI to APPROVED (forbidden by invariant)
        assertFalse(stateMachine.isLegalTransition(TransactionStatus.AI_ANALYSIS, TransactionStatus.APPROVED));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionStatus.class, names = {"APPROVED", "REJECTED", "PAYMENT_FAILED"})
    @DisplayName("Should prevent transitions from terminal states")
    void shouldPreventTransitionsFromTerminalStates(TransactionStatus terminalStatus) {
        transaction.applyStatus(terminalStatus);
        transaction.markAsTerminal();
        
        // No transitions should be allowed from terminal states
        assertFalse(stateMachine.isLegalTransition(terminalStatus, TransactionStatus.EVIDENCE_PENDING));
        assertFalse(stateMachine.isLegalTransition(terminalStatus, TransactionStatus.APPROVED));
        
        // Should throw exception when attempting transition
        assertThrows(IllegalTransactionStateException.class, 
            () -> stateMachine.transition(transaction, TransactionStatus.EVIDENCE_PENDING));
    }

    @Test
    @DisplayName("Should enforce AI_ANALYSIS -> APPROVED prohibition")
    void shouldEnforceAiToApprovedProhibition() {
        transaction.applyStatus(TransactionStatus.AI_ANALYSIS);
        
        IllegalTransactionStateException exception = assertThrows(
            IllegalTransactionStateException.class,
            () -> stateMachine.transition(transaction, TransactionStatus.APPROVED)
        );
        
        assertTrue(exception.getMessage().contains("Direct transition from AI_ANALYSIS to APPROVED is forbidden"));
    }

    @Test
    @DisplayName("Should successfully execute valid transition with audit trail")
    void shouldExecuteValidTransitionWithAuditTrail() {
        // Execute a valid transition
        stateMachine.transition(transaction, TransactionStatus.PAYMENT_RECEIVED, 
                TransitionActor.RAZORPAY_GATEWAY, "Payment confirmed");
        
        assertEquals(TransactionStatus.PAYMENT_RECEIVED, transaction.getStatus());
        assertFalse(transaction.isTerminal());
    }

    @Test
    @DisplayName("Should mark transaction as terminal when transitioning to terminal state")
    void shouldMarkAsTerminalWhenTransitioningToTerminalState() {
        // Transition through valid path to terminal state
        transaction.applyStatus(TransactionStatus.SETTLEMENT_PENDING);

        stateMachine.transition(transaction, TransactionStatus.SETTLED);

        assertEquals(TransactionStatus.SETTLED, transaction.getStatus());
        assertTrue(transaction.isTerminal());
    }

    @Test
    @DisplayName("Should handle OCR failure recovery path")
    void shouldHandleOcrFailureRecoveryPath() {
        transaction.applyStatus(TransactionStatus.OCR_PROCESSING);
        
        // Transition to OCR_FAILED
        stateMachine.transitionToOcrFailed(transaction, "OCR extraction failed");
        assertEquals(TransactionStatus.OCR_FAILED, transaction.getStatus());
        assertFalse(transaction.isTerminal());
        
        // Recover to EVIDENCE_PENDING
        stateMachine.transitionFromOcrFailedToEvidencePending(transaction, "Retry after re-upload");
        assertEquals(TransactionStatus.EVIDENCE_PENDING, transaction.getStatus());
    }

    @Test
    @DisplayName("Should handle AI analysis failure recovery path")
    void shouldHandleAiAnalysisFailureRecoveryPath() {
        transaction.applyStatus(TransactionStatus.AI_ANALYSIS);
        
        // Transition to AI_ANALYSIS_FAILED
        stateMachine.transitionToAiAnalysisFailed(transaction, "AI service timeout");
        assertEquals(TransactionStatus.AI_ANALYSIS_FAILED, transaction.getStatus());
        assertFalse(transaction.isTerminal());
        
        // Recover to EVIDENCE_PENDING
        stateMachine.transitionFromAiFailedToEvidencePending(transaction, "Retry after service recovery");
        assertEquals(TransactionStatus.EVIDENCE_PENDING, transaction.getStatus());
    }

    @Test
    @DisplayName("Should handle compliance officer approval workflow")
    void shouldHandleComplianceOfficerApprovalWorkflow() {
        transaction.applyStatus(TransactionStatus.COMPLIANCE_HOLD);
        
        String reviewerId = UUID.randomUUID().toString();
        String justification = "All compliance checks satisfied after manual review";
        
        stateMachine.approveFromComplianceHold(transaction, reviewerId, justification);
        
        assertEquals(TransactionStatus.APPROVED, transaction.getStatus());
        assertFalse(transaction.isTerminal());
    }

    @Test
    @DisplayName("Should handle compliance officer rejection workflow")
    void shouldHandleComplianceOfficerRejectionWorkflow() {
        transaction.applyStatus(TransactionStatus.COMPLIANCE_HOLD);
        
        String reviewerId = UUID.randomUUID().toString();
        String justification = "Beneficiary verification failed";
        
        stateMachine.rejectFromComplianceHold(transaction, reviewerId, justification);
        
        assertEquals(TransactionStatus.REJECTED, transaction.getStatus());
        assertTrue(transaction.isTerminal());
    }

    @Test
    @DisplayName("Should get valid next states for current status")
    void shouldGetValidNextStatesForCurrentStatus() {
        var nextStates = stateMachine.getValidNextStates(TransactionStatus.CREATED);
        assertEquals(2, nextStates.size());
        assertTrue(nextStates.contains(TransactionStatus.PAYMENT_RECEIVED));
        assertTrue(nextStates.contains(TransactionStatus.PAYMENT_FAILED));
        
        var terminalNextStates = stateMachine.getValidNextStates(TransactionStatus.SETTLED);
        assertTrue(terminalNextStates.isEmpty());
    }
}
