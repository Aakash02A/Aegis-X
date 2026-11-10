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

        dispatcher.dispatch(capturedPayload(transaction), eventId, "WHK-TEST01");

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
    @DisplayName("A replay for a transaction already advanced is recorded as a conflict and not applied")
    void capturedPaymentForAdvancedTransactionIsFlaggedForReview() {
        Transaction transaction = createTransaction(TransactionStatus.EVIDENCE_PENDING);
        String eventId = "evt_replay_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction), eventId, "WHK-TEST02");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.EVIDENCE_PENDING);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.FAILED);
        assertThat(requiredDocumentRepository.findByTransactionId(transaction.getId())).isEmpty();
    }

    @Test
    @DisplayName("a capture with a mismatched amount is failed and cannot advance the transaction")
    void mismatchedAmountIsNotApplied() {
        Transaction transaction = createTransaction(TransactionStatus.CREATED);
        String eventId = "evt_amount_mismatch_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction, "100000", "USD"),
        eventId, "WHK-TEST04");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
        .isEqualTo(TransactionStatus.CREATED);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
        .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("a capture with a mismatched currency is failed and cannot advance the transaction")
    void mismatchedCurrencyIsNotApplied() {
        Transaction transaction = createTransaction(TransactionStatus.CREATED);
        String eventId = "evt_currency_mismatch_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction, "250000", "INR"),
        eventId, "WHK-TEST05");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
        .isEqualTo(TransactionStatus.CREATED);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
        .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("a capture after payment failure is recorded as a conflict, not successful processing")
    void captureAfterPaymentFailureIsFailed() {
        Transaction transaction = createTransaction(TransactionStatus.PAYMENT_FAILED);
        String eventId = "evt_late_capture_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction), eventId, "WHK-TEST06");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
        .isEqualTo(TransactionStatus.PAYMENT_FAILED);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
        .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("a captured payment with the wrong order id is rejected and flagged for review")
    void wrongOrderIdIsRejected() {
        Transaction transaction = createTransaction(TransactionStatus.CREATED);
        String eventId = "evt_wrong_order_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload("order_wrong_" + UUID.randomUUID(), transaction.getReferenceNumber()),
                eventId, "WHK-TEST08");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.CREATED);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("a duplicate payment capture with a new event id is rejected and not reapplied")
    void duplicateCaptureDeliveryIsRejected() {
        Transaction transaction = createTransaction(TransactionStatus.CREATED);
        String firstEventId = "evt_duplicate_first_" + UUID.randomUUID();
        String secondEventId = "evt_duplicate_second_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload(transaction), firstEventId, "WHK-TEST09");
        dispatcher.dispatch(capturedPayload(transaction), secondEventId, "WHK-TEST10");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.EVIDENCE_PENDING);
        assertThat(webhookEventRepository.findByEventId(secondEventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("A captured payment for an unknown transaction is durably marked failed")
    void unknownTransactionIsMarkedFailed() {
        String eventId = "evt_unknown_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload("order_unknown_" + UUID.randomUUID(), "TXN-UNKNOWN-" + UUID.randomUUID()),
                eventId, "WHK-TEST03");

        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("a matching transaction note cannot substitute for a persisted Razorpay order ID")
    void notesCannotReplacePersistedOrderBinding() {
        Transaction transaction = createTransaction(TransactionStatus.CREATED);
        String eventId = "evt_unbound_order_" + UUID.randomUUID();

        dispatcher.dispatch(capturedPayload("order_unbound_" + UUID.randomUUID(),
                transaction.getReferenceNumber()), eventId, "WHK-TEST07");

        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.CREATED);
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
                .razorpayOrderId("order_" + UUID.randomUUID().toString().replace("-", ""))
                .merchant(merchant)
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Global Exporters")
                .purposeCode("P0103")
                .status(status)
                .version(0L)
                .build());
    }

    private String capturedPayload(Transaction transaction) {
        return capturedPayload(transaction, "250000", "USD");
    }

    private String capturedPayload(Transaction transaction, String amountMinor, String currency) {
        return capturedPayload(transaction.getRazorpayOrderId(), transaction.getReferenceNumber(), amountMinor, currency);
    }

    private String capturedPayload(String orderId, String referenceNumber) {
        return capturedPayload(orderId, referenceNumber, "250000", "USD");
    }

    private String capturedPayload(String orderId, String referenceNumber, String amountMinor, String currency) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{" +
                "\"id\":\"pay_" + UUID.randomUUID() + "\",\"amount\":" + amountMinor +
                ",\"currency\":\"" + currency + "\",\"order_id\":\"" + orderId + "\"," +
                "\"notes\":{\"transaction_reference\":\"" + referenceNumber + "\"}}}}}";
    }
}
