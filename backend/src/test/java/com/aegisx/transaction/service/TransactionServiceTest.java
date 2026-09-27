package com.aegisx.transaction.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.audit.service.AuditService;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @Spy
    private TransactionStateMachine transactionStateMachine = new TransactionStateMachine();

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    private TransactionService transactionService;

    private User merchantUser;
    private User otherMerchantUser;
    private Transaction transaction;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        merchantUser = User.builder()
                .fullName("Test Merchant")
                .email("merchant@example.com")
                .role(Role.MERCHANT)
                .build();
        merchantUser.setId(UUID.randomUUID());

        otherMerchantUser = User.builder()
                .fullName("Other Merchant")
                .email("other@example.com")
                .role(Role.MERCHANT)
                .build();
        otherMerchantUser.setId(UUID.randomUUID());

        transactionId = UUID.randomUUID();
        transaction = Transaction.builder()
                .referenceNumber("TXN-20260921-A1B2C3")
                .merchant(merchantUser)
                .amount(new BigDecimal("1500.00"))
                .currency("USD")
                .beneficiaryName("Global Supplies LLC")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .version(0L)
                .build();
        transaction.setId(transactionId);
    }

    @Test
    @DisplayName("Create transaction normalizes currency and purpose code and saves with CREATED status")
    void testCreateTransaction() {
        CreateTransactionRequest request = CreateTransactionRequest.builder()
                .amount(new BigDecimal("2500.50"))
                .currency("usd")
                .beneficiaryName("XYZ Logistics")
                .purposeCode("p0108")
                .build();

        when(userRepository.findByEmail("merchant@example.com")).thenReturn(Optional.of(merchantUser));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        TransactionResponse response = transactionService.createTransaction(request, "merchant@example.com");

        assertNotNull(response);
        assertNotNull(response.getId());
        assertTrue(response.getReferenceNumber().startsWith("TXN-20260921-"));
        assertEquals("USD", response.getCurrency());
        assertEquals("P0108", response.getPurposeCode());
        assertEquals(TransactionStatus.CREATED, response.getStatus());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction captured = captor.getValue();
        assertEquals("USD", captured.getCurrency());
        assertEquals("P0108", captured.getPurposeCode());
    }

    @Test
    @DisplayName("Get transaction returns transaction for authorized merchant")
    void testGetTransactionMerchantSuccess() {
        when(userRepository.findByEmail("merchant@example.com")).thenReturn(Optional.of(merchantUser));
        when(transactionRepository.findByIdAndMerchantId(transactionId, merchantUser.getId()))
                .thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransaction(transactionId, "merchant@example.com", Role.MERCHANT);

        assertNotNull(response);
        assertEquals(transactionId, response.getId());
        assertEquals("TXN-20260921-A1B2C3", response.getReferenceNumber());
    }

    @Test
    @DisplayName("Get transaction throws TransactionNotFoundException when merchant attempts accessing another merchant's transaction")
    void testGetTransactionMerchantForbidden() {
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(otherMerchantUser));
        when(transactionRepository.findByIdAndMerchantId(transactionId, otherMerchantUser.getId()))
                .thenReturn(Optional.empty());

        assertThrows(TransactionNotFoundException.class, () ->
                transactionService.getTransaction(transactionId, "other@example.com", Role.MERCHANT));
    }

    @Test
    @DisplayName("Get transaction succeeds for COMPLIANCE_OFFICER regardless of merchant ownership")
    void testGetTransactionComplianceOfficerSuccess() {
        User complianceOfficer = User.builder()
                .fullName("Compliance Admin")
                .email("compliance@example.com")
                .role(Role.COMPLIANCE_OFFICER)
                .build();
        complianceOfficer.setId(UUID.randomUUID());

        when(userRepository.findByEmail("compliance@example.com")).thenReturn(Optional.of(complianceOfficer));
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        TransactionResponse response = transactionService.getTransaction(transactionId, "compliance@example.com", Role.COMPLIANCE_OFFICER);

        assertNotNull(response);
        assertEquals(transactionId, response.getId());
    }

    @Test
    @DisplayName("List transactions filters by merchantId for MERCHANT role")
    void testListTransactionsForMerchant() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Transaction> page = new PageImpl<>(Collections.singletonList(transaction), pageable, 1);

        when(userRepository.findByEmail("merchant@example.com")).thenReturn(Optional.of(merchantUser));
        when(transactionRepository.findByMerchantId(merchantUser.getId(), pageable)).thenReturn(page);

        Page<TransactionResponse> result = transactionService.listTransactions("merchant@example.com", Role.MERCHANT, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(transactionId, result.getContent().get(0).getId());
    }

    @Test
    @DisplayName("transitionStatus delegates to TransactionStateMachine and saves update")
    void testTransitionStatus() {
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = transactionService.transitionStatus(
                transactionId,
                TransactionStatus.PAYMENT_RECEIVED,
                "RAZORPAY_WEBHOOK",
                "Webhook event received"
        );

        assertEquals(TransactionStatus.PAYMENT_RECEIVED, response.getStatus());
        verify(transactionStateMachine).transition(transaction, TransactionStatus.PAYMENT_RECEIVED, "RAZORPAY_WEBHOOK", "Webhook event received");
        verify(transactionRepository).save(transaction);
    }
}
