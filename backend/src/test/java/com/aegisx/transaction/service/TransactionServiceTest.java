package com.aegisx.transaction.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.auth.service.UserService;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionServiceTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String merchantEmail;
    private String otherMerchantEmail;

    @BeforeEach
    void setUp() {
        merchantEmail = "merchant-" + UUID.randomUUID() + "@example.com";
        otherMerchantEmail = "other-" + UUID.randomUUID() + "@example.com";
        userService.register(registerRequest("Test Merchant", merchantEmail));
        userService.register(registerRequest("Other Merchant", otherMerchantEmail));
    }

    @Test
    void createTransactionNormalizesCurrencyAndPurposeCodeAndPersistsCreatedState() {
        TransactionResponse response = createTransaction("usd", "p0108");

        assertEquals("USD", response.getCurrency());
        assertEquals("P0108", response.getPurposeCode());
        assertEquals(TransactionStatus.CREATED, response.getStatus());
        assertTrue(response.getReferenceNumber().matches("TXN-\\d{8}-[A-Z0-9]+"));
        assertEquals("USD", transactionRepository.findById(response.getId()).orElseThrow().getCurrency());
    }

    @Test
    void getTransactionReturnsTransactionForOwningMerchant() {
        TransactionResponse created = createTransaction("USD", "P0103");

        TransactionResponse response = transactionService.getTransaction(
                created.getId(), merchantEmail, Role.MERCHANT);

        assertEquals(created.getId(), response.getId());
        assertEquals(created.getReferenceNumber(), response.getReferenceNumber());
    }

    @Test
    void merchantCannotReadAnotherMerchantsTransaction() {
        TransactionResponse created = createTransaction("USD", "P0103");

        assertThrows(TransactionNotFoundException.class, () -> transactionService.getTransaction(
                created.getId(), otherMerchantEmail, Role.MERCHANT));
    }

    @Test
    void complianceOfficerCanReadTransactionAcrossMerchantOwnership() {
        TransactionResponse created = createTransaction("USD", "P0103");
        String complianceEmail = "compliance-" + UUID.randomUUID() + "@example.com";
        userRepository.save(User.builder()
                .fullName("Compliance Officer")
                .email(complianceEmail)
                .passwordHash(passwordEncoder.encode("Password@123"))
                .role(Role.COMPLIANCE_OFFICER)
                .build());

        TransactionResponse response = transactionService.getTransaction(
                created.getId(), complianceEmail, Role.COMPLIANCE_OFFICER);

        assertEquals(created.getId(), response.getId());
    }

    @Test
    void listTransactionsIsScopedToMerchant() {
        createTransaction("USD", "P0103");

        Page<TransactionResponse> result = transactionService.listTransactions(
                merchantEmail, Role.MERCHANT, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(merchantEmail,
                transactionRepository.findById(result.getContent().get(0).getId()).orElseThrow()
                        .getMerchant().getEmail());
    }

    @Test
    void transitionStatusPersistsTheStateMachineTransition() {
        TransactionResponse created = createTransaction("USD", "P0103");

        TransactionResponse updated = transactionService.transitionStatus(
                created.getId(), TransactionStatus.PAYMENT_RECEIVED, "RAZORPAY_WEBHOOK", "Webhook event received");

        assertEquals(TransactionStatus.PAYMENT_RECEIVED, updated.getStatus());
        assertEquals(TransactionStatus.PAYMENT_RECEIVED,
                transactionRepository.findById(created.getId()).orElseThrow().getStatus());
    }

    private TransactionResponse createTransaction(String currency, String purposeCode) {
        return transactionService.createTransaction(CreateTransactionRequest.builder()
                .amount(new BigDecimal("2500.50"))
                .currency(currency)
                .beneficiaryName("XYZ Logistics")
                .purposeCode(purposeCode)
                .build(), merchantEmail);
    }

    private static RegisterRequest registerRequest(String fullName, String email) {
        return RegisterRequest.builder()
                .fullName(fullName)
                .email(email)
                .password("Password@123")
                .build();
    }
}
