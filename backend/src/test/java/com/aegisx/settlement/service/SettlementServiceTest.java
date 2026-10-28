package com.aegisx.settlement.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Settlement service integration")
class SettlementServiceTest {

    @Autowired private SettlementService service;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        User merchant = userRepository.saveAndFlush(User.builder()
                .fullName("Settlement Merchant")
                .email("settlement-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-SETTLEMENT-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Settlement Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.APPROVED)
                .build());
    }

    @Test
    @DisplayName("initiates a settlement and writes its audit record")
    void shouldInitiateSettlementSuccessfully() {
        SettlementResponse result = service.initiateSettlement(request(PaymentProcessor.SWIFT), "integration-test");

        assertThat(result.getTransactionId()).isEqualTo(transaction.getId());
        assertThat(result.getPaymentProcessor()).isEqualTo(PaymentProcessor.SWIFT);
        assertThat(result.getSettlementAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.INITIATED);
        assertThat(settlementRepository.findById(result.getId())).isPresent();
    }

    @Test
    @DisplayName("rejects missing, ineligible and duplicate settlement transactions")
    void rejectsInvalidInitiation() {
        SettlementRequest missingTransaction = request(PaymentProcessor.SWIFT);
        missingTransaction.setTransactionId(UUID.randomUUID());
        assertThatThrownBy(() -> service.initiateSettlement(missingTransaction, "test"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Transaction not found");

        Transaction held = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-HELD-" + UUID.randomUUID())
                .merchant(transaction.getMerchant())
                .amount(new BigDecimal("300.00"))
                .currency("USD")
                .beneficiaryName("Held Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .build());
        SettlementRequest ineligible = request(PaymentProcessor.SWIFT);
        ineligible.setTransactionId(held.getId());
        assertThatThrownBy(() -> service.initiateSettlement(ineligible, "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not eligible");

        service.initiateSettlement(request(PaymentProcessor.SWIFT), "test");
        assertThatThrownBy(() -> service.initiateSettlement(request(PaymentProcessor.SWIFT), "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("submits an initiated settlement through the real local processor implementation")
    void shouldProcessSettlementSuccessfully() {
        SettlementResponse initiated = service.initiateSettlement(request(PaymentProcessor.SWIFT), "test");
        SettlementResponse processed = service.processSettlement(initiated.getId());

        assertThat(processed.getStatus()).isEqualTo(SettlementStatus.SUBMITTED);
        assertThat(processed.getProcessorTransactionId()).startsWith("SW");
        assertThat(processed.getPublicMetadata().get("messageType").asText()).isEqualTo("MT103");
        assertThatThrownBy(() -> service.processSettlement(initiated.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in INITIATED status");
    }

    @Test
    @DisplayName("retrieves persisted settlements by id, reference, transaction and status")
    void readsPersistedSettlements() {
        SettlementResponse initiated = service.initiateSettlement(request(PaymentProcessor.SWIFT), "test");

        assertThat(service.getSettlement(initiated.getId()).getId()).isEqualTo(initiated.getId());
        assertThat(service.getSettlementByReference(initiated.getSettlementReference())
                .getSettlementReference()).isEqualTo(initiated.getSettlementReference());
        assertThat(service.getTransactionSettlements(transaction.getId())).hasSize(1);
        assertThat(service.getSettlementsByStatus(SettlementStatus.INITIATED, PageRequest.of(0, 10))
                .getContent()).hasSize(1);
    }

    @Test
    @DisplayName("persists retry and cancel transitions and refuses transitions from terminal states")
    void retryAndCancelTransitionsPersist() {
        SettlementResponse initiated = service.initiateSettlement(request(PaymentProcessor.SWIFT), "test");
        Settlement failed = settlementRepository.findById(initiated.getId()).orElseThrow();
        failed.updateStatus(SettlementStatus.FAILED, "Local processor timeout");
        settlementRepository.saveAndFlush(failed);

        SettlementResponse retried = service.retrySettlement(failed.getId(), "retry");
        assertThat(retried.getStatus()).isEqualTo(SettlementStatus.INITIATED);
        assertThat(retried.getRetryCount()).isEqualTo(1);

        SettlementResponse cancelled = service.cancelSettlement(failed.getId(), "operator requested");
        assertThat(cancelled.getStatus()).isEqualTo(SettlementStatus.CANCELLED);
        assertThatThrownBy(() -> service.cancelSettlement(failed.getId(), "again"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("updates legal statuses and rejects a reversal from a terminal status")
    void updatesSettlementStatusWithTransitionValidation() {
        SettlementResponse initiated = service.initiateSettlement(request(PaymentProcessor.SWIFT), "test");
        SettlementResponse processing = service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.PROCESSING, null, null);
        assertThat(processing.getStatus()).isEqualTo(SettlementStatus.PROCESSING);

        service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.SUBMITTED, null, null);
        service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.PENDING, null, null);
        SettlementResponse completed = service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.COMPLETED, null, null);
        assertThat(completed.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThatThrownBy(() -> service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.INITIATED, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("processes completed settlements through reconciliation")
    void reconcilesCompletedSettlement() {
        SettlementResponse initiated = service.initiateSettlement(request(PaymentProcessor.SWIFT), "test");
        Settlement settlement = settlementRepository.findById(initiated.getId()).orElseThrow();
        settlement.updateStatus(SettlementStatus.COMPLETED, null);
        settlementRepository.saveAndFlush(settlement);

        service.processReconciliation();

        Settlement reconciled = settlementRepository.findById(initiated.getId()).orElseThrow();
        assertThat(reconciled.getReconciledAt()).isNotNull();
    }

    @Test
    @DisplayName("validates request values and builds supported processor request forms")
    void validatesRequestAndRequestFactories() {
        SettlementRequest invalid = SettlementRequest.builder()
                .paymentProcessor(PaymentProcessor.SWIFT)
                .settlementAmount(new BigDecimal("-100"))
                .settlementCurrency("INVALID")
                .build();
        assertThatThrownBy(invalid::validate).isInstanceOf(IllegalArgumentException.class);

        SettlementRequest swift = SettlementRequest.createSwiftSettlement(
                transaction.getId(), new BigDecimal("1000"), "USD", "123456789", "TESTBKXXXX", "John Doe");
        assertThat(swift.getPaymentProcessor()).isEqualTo(PaymentProcessor.SWIFT);
        assertThat(swift.getBeneficiaryAccountNumber()).isEqualTo("123456789");
        assertThat(swift.getBeneficiaryBankCode()).isEqualTo("TESTBKXXXX");

        SettlementRequest card = SettlementRequest.createCardSettlement(
                transaction.getId(), PaymentProcessor.VISA_DIRECT, new BigDecimal("500"),
                "USD", "4111111111111111", "John Doe");
        assertThat(card.getPaymentProcessor()).isEqualTo(PaymentProcessor.VISA_DIRECT);
        assertThat(card.isPrioritySettlement()).isTrue();
    }

    private SettlementRequest request(PaymentProcessor processor) {
        return SettlementRequest.builder()
                .transactionId(transaction.getId())
                .paymentProcessor(processor)
                .settlementAmount(new BigDecimal("1000.00"))
                .settlementCurrency("USD")
                .beneficiaryBankCode("TESTBANK")
                .beneficiaryAccountNumber("1234567890")
                .build();
    }
}
