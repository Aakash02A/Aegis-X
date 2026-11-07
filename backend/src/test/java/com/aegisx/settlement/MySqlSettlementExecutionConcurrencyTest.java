package com.aegisx.settlement;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.settlement.service.SettlementProcessorService;
import com.aegisx.settlement.service.SettlementService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Confirms the real MySQL row lock allows one payout dispatch for overlapping process calls. */
@DisplayName("Settlement execution concurrency on MySQL")
class MySqlSettlementExecutionConcurrencyTest extends MySqlIntegrityTestSupport {

    @Autowired private SettlementService settlementService;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @SpyBean private SettlementProcessorService processorService;

    @Test
    void concurrentProcessingSubmitsExactlyOnce() throws Exception {
        User merchant = userRepository.saveAndFlush(User.builder()
                .fullName("MySQL settlement test merchant")
                .email("mysql-settlement-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-test")
                .role(Role.MERCHANT)
                .build());
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-MYSQL-SETTLEMENT-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Settlement Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.APPROVED)
                .version(0L)
                .build());
        SettlementResponse initiated = settlementService.initiateSettlement(SettlementRequest.createBasic(
                transaction.getId(), PaymentProcessor.SIMULATED, new BigDecimal("1000.00"), "USD"),
                merchant.getEmail());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();

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
                started.countDown();
                try {
                    settlementService.processSettlement(initiated.getId());
                    return true;
                } catch (IllegalStateException e) {
                    return false;
                }
            })));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            releaseProvider.countDown();

            int successfulSubmissions = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    successfulSubmissions++;
                }
            }

            assertThat(successfulSubmissions).isEqualTo(1);
            assertThat(settlementRepository.findById(initiated.getId()).orElseThrow().getStatus())
                    .isEqualTo(SettlementStatus.PENDING);
            assertThatThrownBy(() -> settlementService.processSettlement(initiated.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in INITIATED status");
            verify(processorService, times(1)).submitSettlement(any(Settlement.class));
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }
}
