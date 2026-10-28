package com.aegisx.integration.razorpay.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentCapturedHandlerTest {

    @Autowired private RazorpayWebhookDispatcher dispatcher;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private RequiredDocumentRepository requiredDocumentRepository;

    @Test
    @DisplayName("A captured payment advances the real transaction and initializes evidence")
    void capturedPaymentTransitionsTransaction() {
        Transaction transaction = createTransaction(TransactionStatus.CREATED);
        String eventId = "evt_captured_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction.getReferenceNumber()), eventId, "WHK-TEST01");

        Transaction updated = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.EVIDENCE_PENDING);
        var event = webhookEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.PROCESSED);
        assertThat(event.getPaymentId()).startsWith("pay_");
        assertThat(event.getTransactionReference()).isEqualTo(transaction.getReferenceNumber());
        assertThat(requiredDocumentRepository.findByTransactionId(transaction.getId()))
                .extracting(document -> document.getDocumentType())
                .containsExactlyInAnyOrder(
                        com.aegisx.document.entity.DocumentType.INVOICE,
                        com.aegisx.document.entity.DocumentType.SHIPPING_PROOF,
                        com.aegisx.document.entity.DocumentType.EXPORT_DECLARATION);
    }

    @Test
    @DisplayName("A replay for a transaction already advanced is recorded without another transition")
    void capturedPaymentForAdvancedTransactionIsIdempotentlyProcessed() {
        Transaction transaction = createTransaction(TransactionStatus.EVIDENCE_PENDING);
        String eventId = "evt_replay_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction.getReferenceNumber()), eventId, "WHK-TEST02");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.EVIDENCE_PENDING);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.PROCESSED);
        assertThat(requiredDocumentRepository.findByTransactionId(transaction.getId())).isEmpty();
    }

    @Test
    @DisplayName("A captured payment for an unknown transaction is durably marked failed")
    void unknownTransactionIsMarkedFailed() {
        String eventId = "evt_unknown_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload("TXN-UNKNOWN-" + UUID.randomUUID()), eventId, "WHK-TEST03");

        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    private Transaction createTransaction(TransactionStatus status) {
        User merchant = userRepository.saveAndFlush(User.builder()
                .email("razorpay-" + UUID.randomUUID() + "@aegisx.test")
                .passwordHash("not-used-by-this-service")
                .fullName("Razorpay Test Merchant")
                .role(Role.MERCHANT)
                .build());
        return transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-RZP-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Global Exporters")
                .purposeCode("P0103")
                .status(status)
                .version(0L)
                .build());
    }

    private String capturedPayload(String referenceNumber) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{" +
                "\"id\":\"pay_" + UUID.randomUUID() + "\",\"amount\":250000,\"currency\":\"USD\"," +
                "\"notes\":{\"transaction_reference\":\"" + referenceNumber + "\"}}}}}";
    }
}
