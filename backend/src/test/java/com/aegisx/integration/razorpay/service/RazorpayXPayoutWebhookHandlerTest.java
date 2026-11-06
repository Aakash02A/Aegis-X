package com.aegisx.integration.razorpay.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RazorpayXPayoutWebhookHandlerTest {

    @Autowired private RazorpayWebhookDispatcher dispatcher;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Test
    void processesAProviderConfirmedPayout() {
        Settlement settlement = createSettlement("pout_processed_" + UUID.randomUUID(), "pending");
        String eventId = "evt_payout_processed_" + UUID.randomUUID();

        dispatcher.dispatch(payoutPayload(settlement, "processed", "100000"), eventId, "WHK-PAYOUT01");

        Settlement updated = settlementRepository.findById(settlement.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.PROCESSED);
    }

    @Test
    void doesNotApplyAWebhookWithAMismatchedAmount() {
        Settlement settlement = createSettlement("pout_mismatch_" + UUID.randomUUID(), "pending");
        String eventId = "evt_payout_mismatch_" + UUID.randomUUID();

        dispatcher.dispatch(payoutPayload(settlement, "processed", "100001"), eventId, "WHK-PAYOUT02");

        assertThat(settlementRepository.findById(settlement.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.PENDING);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.FAILED);
    }

    @Test
    void lateProviderConfirmationRecoversAnAmbiguousSubmission() {
        Settlement settlement = createSettlement(null, "failed");
        String payoutId = "pout_late_" + UUID.randomUUID();
        String eventId = "evt_late_payout_" + UUID.randomUUID();

        dispatcher.dispatch(payoutPayload(settlement, payoutId, "processed", "100000"),
                eventId, "WHK-PAYOUT03");

        Settlement updated = settlementRepository.findById(settlement.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(updated.getProcessorTransactionId()).isEqualTo(payoutId);
        assertThat(webhookEventRepository.findByEventId(eventId).orElseThrow().getProcessingStatus())
                .isEqualTo(WebhookProcessingStatus.PROCESSED);
    }

    private Settlement createSettlement(String payoutId, String status) {
        User merchant = userRepository.saveAndFlush(User.builder()
                .email("razorpayx-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .fullName("RazorpayX Test Merchant")
                .role(Role.MERCHANT)
                .build());
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-RZPX-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .beneficiaryName("Payout Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.APPROVED)
                .build());
        Settlement settlement = Settlement.builder()
                .transaction(transaction)
                .settlementReference("STL-RZPX-" + UUID.randomUUID().toString().substring(0, 8))
                .paymentProcessor(PaymentProcessor.RAZORPAYX)
                .processorTransactionId(payoutId)
                .status(SettlementStatus.valueOf(status.toUpperCase()))
                .settlementAmount(new BigDecimal("1000.00"))
                .settlementCurrency("INR")
                .initiatedAt(LocalDateTime.now())
                .initiatedBy("test")
                .build();
        return settlementRepository.saveAndFlush(settlement);
    }

    private String payoutPayload(Settlement settlement, String status, String amountMinor) {
        return payoutPayload(settlement, settlement.getProcessorTransactionId(), status, amountMinor);
    }

    private String payoutPayload(Settlement settlement, String payoutId, String status, String amountMinor) {
        return "{\"event\":\"payout." + status + "\",\"payload\":{\"payout\":{\"entity\":{"
                + "\"id\":\"" + payoutId + "\","
                + "\"amount\":" + amountMinor + ",\"currency\":\"INR\","
                + "\"status\":\"" + status + "\","
                + "\"reference_id\":\"" + settlement.getSettlementReference() + "\","
                + "\"utr\":\"UTR-TEST\"}}}}";
    }
}
