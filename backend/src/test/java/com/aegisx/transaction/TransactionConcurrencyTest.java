package com.aegisx.transaction;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class TransactionConcurrencyTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Concurrent update on stale entity version triggers ObjectOptimisticLockingFailureException")
    void testOptimisticLockingFailureOnConcurrentModification() {
        User merchant = User.builder()
                .fullName("Concurrent Merchant")
                .email("concurrent_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .passwordHash("hashed_pwd")
                .role(Role.MERCHANT)
                .build();
        merchant = userRepository.save(merchant);

        Transaction original = Transaction.builder()
                .referenceNumber("TXN-20260921-CONC01")
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Supplier A")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();
        original = transactionRepository.save(original);

        // Thread A loads transaction (version 0)
        Transaction threadATx = transactionRepository.findById(original.getId()).orElseThrow();

        // Thread B loads transaction (version 0)
        Transaction threadBTx = transactionRepository.findById(original.getId()).orElseThrow();

        // Thread A updates and saves -> version becomes 1
        threadATx.applyStatus(TransactionStatus.PAYMENT_RECEIVED);
        transactionRepository.save(threadATx);

        // Thread B attempts to update using stale version 0 -> fails with OptimisticLockingFailure
        threadBTx.applyStatus(TransactionStatus.EVIDENCE_PENDING);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> {
            transactionRepository.save(threadBTx);
        });
    }
}
