package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.audit.service.AuditService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentCapturedHandlerTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AuditService auditService;

    @Spy
    private TransactionStateMachine transactionStateMachine = new TransactionStateMachine();

    @InjectMocks
    private PaymentCapturedHandler paymentCapturedHandler;

    private Transaction transaction;
    private WebhookEvent webhookEvent;

    @BeforeEach
    void setUp() {
        transaction = Transaction.builder()
                .referenceNumber("TXN-20260921-A1B2C3")
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Global Exporters")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();
        transaction.setId(UUID.randomUUID());

        webhookEvent = WebhookEvent.builder()
                .eventId("pay_test123-payment.captured")
                .eventType("payment.captured")
                .processingStatus(WebhookProcessingStatus.RECEIVED)
                .build();
    }

    @Test
    @DisplayName("Valid payment.captured advances CREATED -> PAYMENT_RECEIVED -> EVIDENCE_PENDING")
    void testValidPaymentCapturedTransitionsTransaction() {
        RazorpayWebhookPayload payload = RazorpayWebhookPayload.builder()
                .event("payment.captured")
                .payload(RazorpayWebhookPayload.PayloadContainer.builder()
                        .payment(RazorpayWebhookPayload.PaymentWrapper.builder()
                                .entity(RazorpayWebhookPayload.PaymentEntity.builder()
                                        .id("pay_test123")
                                        .amount(new BigDecimal("250000"))
                                        .currency("USD")
                                        .notes(Map.of("transaction_reference", "TXN-20260921-A1B2C3"))
                                        .build())
                                .build())
                        .build())
                .build();

        when(transactionRepository.findByReferenceNumber("TXN-20260921-A1B2C3"))
                .thenReturn(Optional.of(transaction));

        paymentCapturedHandler.handle(payload, webhookEvent, "WHK-TEST01");

        assertEquals(TransactionStatus.EVIDENCE_PENDING, transaction.getStatus());
        assertEquals(WebhookProcessingStatus.PROCESSED, webhookEvent.getProcessingStatus());
        assertEquals("pay_test123", webhookEvent.getPaymentId());
        assertEquals("TXN-20260921-A1B2C3", webhookEvent.getTransactionReference());

        verify(transactionRepository).save(transaction);
        verify(transactionStateMachine).transition(transaction, TransactionStatus.PAYMENT_RECEIVED, TransitionActor.RAZORPAY_GATEWAY, "Razorpay payment captured: pay_test123");
        verify(transactionStateMachine).transition(transaction, TransactionStatus.EVIDENCE_PENDING, TransitionActor.SYSTEM, "Payment confirmed; awaiting merchant compliance evidence");
    }

    @Test
    @DisplayName("Payment.captured for already transitioned transaction is marked PROCESSED without error")
    void testPaymentCapturedForAlreadyAdvancedTransaction() {
        transaction.applyStatus(TransactionStatus.EVIDENCE_PENDING);

        RazorpayWebhookPayload payload = RazorpayWebhookPayload.builder()
                .event("payment.captured")
                .payload(RazorpayWebhookPayload.PayloadContainer.builder()
                        .payment(RazorpayWebhookPayload.PaymentWrapper.builder()
                                .entity(RazorpayWebhookPayload.PaymentEntity.builder()
                                        .id("pay_test123")
                                        .notes(Map.of("transaction_reference", "TXN-20260921-A1B2C3"))
                                        .build())
                                .build())
                        .build())
                .build();

        when(transactionRepository.findByReferenceNumber("TXN-20260921-A1B2C3"))
                .thenReturn(Optional.of(transaction));

        paymentCapturedHandler.handle(payload, webhookEvent, "WHK-TEST02");

        assertEquals(TransactionStatus.EVIDENCE_PENDING, transaction.getStatus());
        assertEquals(WebhookProcessingStatus.PROCESSED, webhookEvent.getProcessingStatus());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Payment.captured for unknown transaction sets FAILED status without throwing")
    void testUnknownTransactionSetsFailedStatus() {
        RazorpayWebhookPayload payload = RazorpayWebhookPayload.builder()
                .event("payment.captured")
                .payload(RazorpayWebhookPayload.PayloadContainer.builder()
                        .payment(RazorpayWebhookPayload.PaymentWrapper.builder()
                                .entity(RazorpayWebhookPayload.PaymentEntity.builder()
                                        .id("pay_test123")
                                        .notes(Map.of("transaction_reference", "TXN-UNKNOWN"))
                                        .build())
                                .build())
                        .build())
                .build();

        when(transactionRepository.findByReferenceNumber("TXN-UNKNOWN"))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> paymentCapturedHandler.handle(payload, webhookEvent, "WHK-TEST03"));
        assertEquals(WebhookProcessingStatus.FAILED, webhookEvent.getProcessingStatus());
    }
}
