package com.aegisx.transaction.service;

import com.aegisx.common.exception.IllegalTransactionStateException;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionStateMachineTest {

    private TransactionStateMachine stateMachine;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        stateMachine = new TransactionStateMachine();
        transaction = Transaction.builder()
                .referenceNumber("TXN-20260921-A1B2C3")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();
        transaction.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Legal transition: CREATED -> PAYMENT_RECEIVED")
    void testLegalTransitionCreatedToPaymentReceived() {
        stateMachine.transition(transaction, TransactionStatus.PAYMENT_RECEIVED, "RAZORPAY_WEBHOOK", "Payment confirmed");
        assertEquals(TransactionStatus.PAYMENT_RECEIVED, transaction.getStatus());
    }

    @Test
    @DisplayName("Legal transition: PAYMENT_RECEIVED -> EVIDENCE_PENDING")
    void testLegalTransitionPaymentReceivedToEvidencePending() {
        transaction.applyStatus(TransactionStatus.PAYMENT_RECEIVED);
        stateMachine.transition(transaction, TransactionStatus.EVIDENCE_PENDING, "SYSTEM", "Awaiting invoice");
        assertEquals(TransactionStatus.EVIDENCE_PENDING, transaction.getStatus());
    }

    @Test
    @DisplayName("Legal transition: EVIDENCE_PENDING -> OCR_PROCESSING")
    void testLegalTransitionEvidencePendingToOcrProcessing() {
        transaction.applyStatus(TransactionStatus.EVIDENCE_PENDING);
        stateMachine.transition(transaction, TransactionStatus.OCR_PROCESSING, "DOCUMENT_SERVICE", "Invoice uploaded");
        assertEquals(TransactionStatus.OCR_PROCESSING, transaction.getStatus());
    }

    @Test
    @DisplayName("Legal transition: OCR_PROCESSING -> AI_ANALYSIS")
    void testLegalTransitionOcrProcessingToAiAnalysis() {
        transaction.applyStatus(TransactionStatus.OCR_PROCESSING);
        stateMachine.transition(transaction, TransactionStatus.AI_ANALYSIS, "OCR_PIPELINE", "Text extracted");
        assertEquals(TransactionStatus.AI_ANALYSIS, transaction.getStatus());
    }

    @Test
    @DisplayName("Legal transition: AI_ANALYSIS -> POLICY_EVALUATION")
    void testLegalTransitionAiAnalysisToPolicyEvaluation() {
        transaction.applyStatus(TransactionStatus.AI_ANALYSIS);
        stateMachine.transition(transaction, TransactionStatus.POLICY_EVALUATION, "SPRING_AI", "Reasoning completed");
        assertEquals(TransactionStatus.POLICY_EVALUATION, transaction.getStatus());
    }

    @Test
    @DisplayName("Legal transition: POLICY_EVALUATION -> APPROVED")
    void testLegalTransitionPolicyEvaluationToApproved() {
        transaction.applyStatus(TransactionStatus.POLICY_EVALUATION);
        stateMachine.transition(transaction, TransactionStatus.APPROVED, "POLICY_ENGINE", "All rules passed");
        assertEquals(TransactionStatus.APPROVED, transaction.getStatus());
    }

    @Test
    @DisplayName("Legal transition: POLICY_EVALUATION -> COMPLIANCE_HOLD")
    void testLegalTransitionPolicyEvaluationToComplianceHold() {
        transaction.applyStatus(TransactionStatus.POLICY_EVALUATION);
        stateMachine.transition(transaction, TransactionStatus.COMPLIANCE_HOLD, "POLICY_ENGINE", "Rule PR-03 hold");
        assertEquals(TransactionStatus.COMPLIANCE_HOLD, transaction.getStatus());
    }

    @ParameterizedTest(name = "Illegal transition {0} -> {1} must throw exception")
    @CsvSource({
            "CREATED, APPROVED",
            "CREATED, AI_ANALYSIS",
            "CREATED, POLICY_EVALUATION",
            "PAYMENT_RECEIVED, APPROVED",
            "EVIDENCE_PENDING, APPROVED",
            "OCR_PROCESSING, APPROVED",
            "AI_ANALYSIS, APPROVED",
            "APPROVED, CREATED",
            "APPROVED, PAYMENT_RECEIVED",
            "COMPLIANCE_HOLD, CREATED",
            "COMPLIANCE_HOLD, APPROVED",
            "COMPLIANCE_HOLD, PAYMENT_RECEIVED"
    })
    void testIllegalTransitions(TransactionStatus from, TransactionStatus to) {
        transaction.applyStatus(from);
        assertThrows(IllegalTransactionStateException.class, () ->
                stateMachine.transition(transaction, to, "TEST_ACTOR", "Illegal transition test"));
    }

    @Test
    @DisplayName("Null arguments must throw IllegalArgumentException")
    void testNullArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                stateMachine.transition(null, TransactionStatus.PAYMENT_RECEIVED));
        assertThrows(IllegalArgumentException.class, () ->
                stateMachine.transition(transaction, null));
    }
}
