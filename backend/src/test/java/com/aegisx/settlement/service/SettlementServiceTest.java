package com.aegisx.settlement.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.repository.AuditEventRepository;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Settlement service integration")
class SettlementServiceTest {

    @Autowired private SettlementService service;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @SpyBean private SettlementProcessorService processorService;

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        reset(processorService);
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
    @DisplayName("initiates a simulated settlement and writes its audit record")
    void shouldInitiateSettlementSuccessfully() {
        SettlementResponse result = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "integration-test");

        assertThat(result.getTransactionId()).isEqualTo(transaction.getId());
        assertThat(result.getPaymentProcessor()).isEqualTo(PaymentProcessor.SIMULATED);
        assertThat(result.getProcessorDisplayName()).contains("SIMULATED");
        assertThat(result.getSettlementAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.INITIATED);
        assertThat(settlementRepository.findById(result.getId())).isPresent();
    }

    @Test
    @DisplayName("rejects missing, ineligible and duplicate settlement transactions")
    void rejectsInvalidInitiation() {
        SettlementRequest missingTransaction = request(PaymentProcessor.SIMULATED);
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
        SettlementRequest ineligible = request(PaymentProcessor.SIMULATED);
        ineligible.setTransactionId(held.getId());
        assertThatThrownBy(() -> service.initiateSettlement(ineligible, "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not eligible");

        service.initiateSettlement(request(PaymentProcessor.SIMULATED), "test");
        assertThatThrownBy(() -> service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("keeps a simulated settlement pending without provider execution evidence")
    void simulatedSettlementNeverReportsSubmissionOrReconciliation() {
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test");
        SettlementResponse processed = service.processSettlement(initiated.getId());

        assertThat(processed.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(processed.getProcessorTransactionId()).isNull();
        assertThat(processed.getPublicMetadata().get("executionMode").asText()).isEqualTo("SIMULATED");
        assertThat(processed.getPublicMetadata().get("moneyMoved").asBoolean()).isFalse();
        assertThat(processed.getStatus()).isNotIn(SettlementStatus.SUBMITTED, SettlementStatus.RECONCILED);
        assertThatThrownBy(() -> service.processSettlement(initiated.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in INITIATED status");
    }

    @Test
    @DisplayName("rejects unsupported legacy processors before creating settlement rows")
    void refusesUnsupportedLegacyProcessor() {
        assertThatThrownBy(() -> service.initiateSettlement(request(PaymentProcessor.SWIFT), "test"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No settlement integration");
        assertThat(settlementRepository.findByTransactionId(transaction.getId())).isEmpty();
    }

    @Test
    @DisplayName("retrieves persisted settlements by id, reference, transaction and status")
    void readsPersistedSettlements() {
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test");

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
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test");
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
    @DisplayName("does not retry a RazorpayX settlement after the provider assigned a payout ID")
    void refusesRetryWhenProviderPayoutMayAlreadyExist() {
        Settlement payout = Settlement.createSettlement(transaction, PaymentProcessor.RAZORPAYX,
                new BigDecimal("1000.00"), "INR", "test");
        payout.setProcessorDetails("pout_test_existing", null);
        payout.updateStatus(SettlementStatus.FAILED, "Local processing failed after provider response");
        Settlement saved = settlementRepository.saveAndFlush(payout);

        assertThatThrownBy(() -> service.retrySettlement(saved.getId(), "retry requested"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconcile the existing payout before retrying");
        assertThat(settlementRepository.findById(saved.getId()).orElseThrow().getRetryCount()).isZero();
    }

    @Test
    @DisplayName("updates legal statuses and rejects a reversal from a terminal status")
    void updatesSettlementStatusWithTransitionValidation() {
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test");
        SettlementResponse processing = service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.PROCESSING, null, null);
        assertThat(processing.getStatus()).isEqualTo(SettlementStatus.PROCESSING);

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
    @DisplayName("does not allow status-only updates to claim provider submission or reconciliation")
    void refusesStatusOnlySubmissionAndReconciliation() {
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test");
        service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.PROCESSING, null, null);

        assertThatThrownBy(() -> service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.SUBMITTED, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("real provider evidence");
        assertThatThrownBy(() -> service.updateSettlementStatus(
                initiated.getSettlementReference(), SettlementStatus.RECONCILED, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("real provider evidence");
        assertThat(settlementRepository.findById(initiated.getId()).orElseThrow().getStatus())
                .isEqualTo(SettlementStatus.PROCESSING);
    }

    @Test
    @DisplayName("keeps settlements without reconciliation evidence unreconciled")
    void refusesUnverifiedReconciliation() {
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "test");
        Settlement settlement = settlementRepository.findById(initiated.getId()).orElseThrow();
        settlement.updateStatus(SettlementStatus.COMPLETED, null);
        settlementRepository.saveAndFlush(settlement);

        service.processReconciliation();

        Settlement reconciled = settlementRepository.findById(initiated.getId()).orElseThrow();
        assertThat(reconciled.getStatus()).isNotEqualTo(SettlementStatus.RECONCILED);
        assertThat(reconciled.getStatus()).isEqualTo(SettlementStatus.MANUAL_REVIEW);
        assertThat(reconciled.getReconciledAt()).isNull();
    }

    @Test
    @DisplayName("rejects amounts above the approved amount and zero or negative amounts with audit entries")
    void rejectsInvalidAmountsAndAuditsRejections() {
        for (BigDecimal amount : List.of(
                new BigDecimal("1000.01"), BigDecimal.ZERO, new BigDecimal("-1.00"))) {
            SettlementRequest invalid = request(PaymentProcessor.SIMULATED);
            invalid.setSettlementAmount(amount);
            assertThatThrownBy(() -> service.initiateSettlement(invalid, "test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(auditEventRepository.findByTransactionIdAndEventTypeOrderByCreatedAtAsc(
                transaction.getId(), AuditEventType.SETTLEMENT_FAILED, PageRequest.of(0, 10))
                .getContent()).hasSize(3);
        assertThat(settlementRepository.findByTransactionId(transaction.getId())).isEmpty();
    }

    @Test
    @DisplayName("validates basic settlement request values")
    void validatesBasicSettlementRequest() {
        SettlementRequest invalid = SettlementRequest.createBasic(
                null, PaymentProcessor.SIMULATED, new BigDecimal("500.00"), "USD");
        assertThatThrownBy(invalid::validate)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Transaction ID");

        SettlementRequest valid = SettlementRequest.createBasic(
                transaction.getId(), PaymentProcessor.SIMULATED, new BigDecimal("500.00"), "USD");
        valid.validate();
        assertThat(valid.getPaymentProcessor()).isEqualTo(PaymentProcessor.SIMULATED);
        assertThat(valid.getSettlementAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("allows exactly one of ten concurrent settlement creations per transaction")
    void concurrentDuplicateCreationPersistsExactlyOneSettlement() throws Exception {
        int attempts = 10;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            IntStream.range(0, attempts).forEach(index -> results.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent settlement test did not start");
                }
                try {
                    service.initiateSettlement(request(PaymentProcessor.SIMULATED), "concurrent-test-" + index);
                    return true;
                } catch (IllegalStateException | DataIntegrityViolationException e) {
                    return false;
                }
            })));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long successfulCreates = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successfulCreates++;
                }
            }

            assertThat(successfulCreates).isEqualTo(1);
            assertThat(settlementRepository.findByTransactionId(transaction.getId())).hasSize(1);
        } finally {
            executor.shutdownNow();
            settlementRepository.deleteAll(settlementRepository.findByTransactionId(transaction.getId()));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("two overlapping direct processing calls submit one settlement once")
    void concurrentProcessingSubmitsExactlyOnce() throws Exception {
        SettlementResponse initiated = service.initiateSettlement(
                request(PaymentProcessor.SIMULATED), "concurrent-processing-test");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        List<Future<SettlementResponse>> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            providerEntered.countDown();
            if (!releaseProvider.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent payout test did not release the first call");
            }
            return invocation.callRealMethod();
        }).when(processorService).submitSettlement(any(Settlement.class));

        try {
            IntStream.range(0, 2).forEach(index -> results.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent settlement test did not start");
                }
                try {
                    return service.processSettlement(initiated.getId());
                } catch (IllegalStateException e) {
                    return null;
                }
            })));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            releaseProvider.countDown();

            List<SettlementResponse> completed = new ArrayList<>();
            for (Future<SettlementResponse> result : results) {
                SettlementResponse response = result.get(20, TimeUnit.SECONDS);
                if (response != null) {
                    completed.add(response);
                }
            }

            assertThat(completed).hasSize(1);
            assertThat(completed.get(0).getStatus()).isEqualTo(SettlementStatus.PENDING);
            assertThat(settlementRepository.findById(initiated.getId()).orElseThrow().getStatus())
                    .isEqualTo(SettlementStatus.PENDING);
            assertThatThrownBy(() -> service.processSettlement(initiated.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in INITIATED status");
            verify(processorService, times(1)).submitSettlement(any(Settlement.class));
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
            settlementRepository.deleteById(initiated.getId());
        }
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
