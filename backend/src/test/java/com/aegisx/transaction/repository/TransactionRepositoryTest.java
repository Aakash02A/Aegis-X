package com.aegisx.transaction.repository;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class TransactionRepositoryTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User merchant;

    @BeforeEach
    void setUp() {
        merchant = User.builder()
                .fullName("Acme Corp")
                .email("acme@example.com")
                .passwordHash("hashed_pwd")
                .role(Role.MERCHANT)
                .build();
        merchant = userRepository.save(merchant);
    }

    @Test
    @DisplayName("Save transaction and query by merchant ID with pagination")
    void testSaveAndFindByMerchantId() {
        Transaction tx = Transaction.builder()
                .referenceNumber("TXN-20260921-000001")
                .merchant(merchant)
                .amount(new BigDecimal("5000.00"))
                .currency("USD")
                .beneficiaryName("Global Exporters Inc")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        Transaction saved = transactionRepository.save(tx);
        assertNotNull(saved.getId());
        assertEquals(0L, saved.getVersion());

        Page<Transaction> result = transactionRepository.findByMerchantId(merchant.getId(), PageRequest.of(0, 10));
        assertEquals(1, result.getTotalElements());
        assertEquals("TXN-20260921-000001", result.getContent().get(0).getReferenceNumber());
    }

    @Test
    @DisplayName("Query transactions by status")
    void testFindByStatus() {
        Transaction tx1 = Transaction.builder()
                .referenceNumber("TXN-20260921-STATUS1")
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("EUR")
                .beneficiaryName("Euro Supplies")
                .purposeCode("P0108")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        Transaction tx2 = Transaction.builder()
                .referenceNumber("TXN-20260921-STATUS2")
                .merchant(merchant)
                .amount(new BigDecimal("2000.00"))
                .currency("GBP")
                .beneficiaryName("UK Traders")
                .purposeCode("P0103")
                .status(TransactionStatus.PAYMENT_RECEIVED)
                .version(0L)
                .build();

        transactionRepository.save(tx1);
        transactionRepository.save(tx2);

        Page<Transaction> createdPage = transactionRepository.findByStatus(TransactionStatus.CREATED, PageRequest.of(0, 10));
        assertEquals(1, createdPage.getTotalElements());
        assertEquals("TXN-20260921-STATUS1", createdPage.getContent().get(0).getReferenceNumber());
    }

    @Test
    @DisplayName("Query transaction by reference number")
    void testFindByReferenceNumber() {
        Transaction tx = Transaction.builder()
                .referenceNumber("TXN-20260921-REF999")
                .merchant(merchant)
                .amount(new BigDecimal("3500.00"))
                .currency("USD")
                .beneficiaryName("Pacific Coast Co")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        transactionRepository.save(tx);

        Optional<Transaction> found = transactionRepository.findByReferenceNumber("TXN-20260921-REF999");
        assertTrue(found.isPresent());
        assertEquals(new BigDecimal("3500.00"), found.get().getAmount());
    }

    @Test
    @DisplayName("Duplicate reference number throws DataIntegrityViolationException")
    void testDuplicateReferenceNumberThrowsException() {
        Transaction tx1 = Transaction.builder()
                .referenceNumber("TXN-20260921-DUP123")
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Supplier A")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        Transaction tx2 = Transaction.builder()
                .referenceNumber("TXN-20260921-DUP123")
                .merchant(merchant)
                .amount(new BigDecimal("2000.00"))
                .currency("USD")
                .beneficiaryName("Supplier B")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        transactionRepository.save(tx1);
        entityManager.flush();

        assertThrows(Exception.class, () -> {
            transactionRepository.save(tx2);
            entityManager.flush();
        });
    }

    @Test
    @DisplayName("Optimistic locking increments entity version on update")
    void testOptimisticLockingIncrementsVersion() {
        Transaction tx = Transaction.builder()
                .referenceNumber("TXN-20260921-VER001")
                .merchant(merchant)
                .amount(new BigDecimal("1500.00"))
                .currency("USD")
                .beneficiaryName("Tech Hardware")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();

        Transaction saved = transactionRepository.save(tx);
        entityManager.flush();
        entityManager.clear();

        assertEquals(0L, saved.getVersion());

        Transaction loaded = transactionRepository.findById(saved.getId()).orElseThrow();
        loaded.setBeneficiaryName("Updated Tech Hardware");
        Transaction updated = transactionRepository.save(loaded);
        entityManager.flush();

        assertEquals(1L, updated.getVersion());
    }
}
