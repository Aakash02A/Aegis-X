package com.aegisx.settlement.service;

import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.audit.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for SettlementService.
 * Tests settlement processing, status management, and business logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementService Tests")
class SettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;
    
    @Mock
    private TransactionRepository transactionRepository;
    
    @Mock
    private SettlementProcessorService settlementProcessorService;
    
    @Mock
    private AuditService auditService;

    @InjectMocks
    private SettlementService settlementService;

    private ObjectMapper objectMapper;
    private Transaction testTransaction;
    private Settlement testSettlement;
    private SettlementRequest testRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        // Create test transaction
        testTransaction = new Transaction();
        testTransaction.setId(UUID.randomUUID());
        testTransaction.setReferenceNumber("TXN-001");
        testTransaction.setAmount(BigDecimal.valueOf(1000.00));
        testTransaction.applyStatus(TransactionStatus.APPROVED);
        testTransaction.setCreatedAt(java.time.Instant.now());

        // Create test settlement
        testSettlement = Settlement.createSettlement(
                testTransaction,
                PaymentProcessor.SWIFT,
                BigDecimal.valueOf(1000.00),
                "USD",
                "test-user"
        );
        testSettlement.setId(UUID.randomUUID());

        // Create test request
        testRequest = SettlementRequest.builder()
                .transactionId(testTransaction.getId())
                .paymentProcessor(PaymentProcessor.SWIFT)
                .settlementAmount(BigDecimal.valueOf(1000.00))
                .settlementCurrency("USD")
                .beneficiaryBankCode("TESTBANK")
                .beneficiaryAccountNumber("1234567890")
                .build();
    }

    @Test
    @DisplayName("Should initiate settlement successfully")
    void shouldInitiateSettlementSuccessfully() {
        // Given
        when(transactionRepository.findById(testTransaction.getId())).thenReturn(Optional.of(testTransaction));
        when(settlementRepository.existsByTransactionId(testTransaction.getId())).thenReturn(false);
        when(settlementRepository.save(any(Settlement.class))).thenReturn(testSettlement);

        // When
        SettlementResponse result = settlementService.initiateSettlement(testRequest, "test-user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTransactionId()).isEqualTo(testTransaction.getId());
        assertThat(result.getPaymentProcessor()).isEqualTo(PaymentProcessor.SWIFT);
        assertThat(result.getSettlementAmount()).isEqualTo(BigDecimal.valueOf(1000.00));
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.INITIATED);
        
        verify(settlementRepository).save(any(Settlement.class));
        verify(auditService).logEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception when transaction not found")
    void shouldThrowExceptionWhenTransactionNotFound() {
        // Given
        when(transactionRepository.findById(testTransaction.getId())).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> settlementService.initiateSettlement(testRequest, "test-user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Should throw exception when settlement already exists")
    void shouldThrowExceptionWhenSettlementAlreadyExists() {
        // Given
        when(transactionRepository.findById(testTransaction.getId())).thenReturn(Optional.of(testTransaction));
        when(settlementRepository.existsByTransactionId(testTransaction.getId())).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> settlementService.initiateSettlement(testRequest, "test-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Settlement already exists");
    }

    @Test
    @DisplayName("Should process settlement successfully")
    void shouldProcessSettlementSuccessfully() throws Exception {
        // Given
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));
        
        SettlementService.SettlementProcessorResult processorResult = 
                SettlementService.SettlementProcessorResult.builder()
                        .processorTransactionId("PROC-123")
                        .success(true)
                        .expectedCompletion(LocalDateTime.now().plusHours(24))
                        .metadata(objectMapper.createObjectNode())
                        .build();
        
        when(settlementProcessorService.submitSettlement(testSettlement)).thenReturn(processorResult);
        when(settlementRepository.save(any(Settlement.class))).thenReturn(testSettlement);

        // When
        SettlementResponse result = settlementService.processSettlement(testSettlement.getId());

        // Then
        assertThat(result).isNotNull();
        verify(settlementProcessorService).submitSettlement(testSettlement);
        verify(settlementRepository, atLeast(2)).save(any(Settlement.class));
        verify(auditService).logEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception when processing settlement not in INITIATED status")
    void shouldThrowExceptionWhenProcessingSettlementNotInInitiatedStatus() {
        // Given
        testSettlement.updateStatus(SettlementStatus.COMPLETED, null);
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));

        // When & Then
        assertThatThrownBy(() -> settlementService.processSettlement(testSettlement.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in INITIATED status");
    }

    @Test
    @DisplayName("Should get settlement by ID")
    void shouldGetSettlementById() {
        // Given
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));

        // When
        SettlementResponse result = settlementService.getSettlement(testSettlement.getId());

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(testSettlement.getId());
        verify(settlementRepository).findById(testSettlement.getId());
    }

    @Test
    @DisplayName("Should get settlement by reference")
    void shouldGetSettlementByReference() {
        // Given
        String reference = testSettlement.getSettlementReference();
        when(settlementRepository.findBySettlementReference(reference)).thenReturn(Optional.of(testSettlement));

        // When
        SettlementResponse result = settlementService.getSettlementByReference(reference);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getSettlementReference()).isEqualTo(reference);
        verify(settlementRepository).findBySettlementReference(reference);
    }

    @Test
    @DisplayName("Should get transaction settlements")
    void shouldGetTransactionSettlements() {
        // Given
        UUID transactionId = testTransaction.getId();
        List<Settlement> settlements = List.of(testSettlement);
        when(settlementRepository.findByTransactionId(transactionId)).thenReturn(settlements);

        // When
        List<SettlementResponse> result = settlementService.getTransactionSettlements(transactionId);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTransactionId()).isEqualTo(transactionId);
        verify(settlementRepository).findByTransactionId(transactionId);
    }

    @Test
    @DisplayName("Should get settlements by status with pagination")
    void shouldGetSettlementsByStatusWithPagination() {
        // Given
        SettlementStatus status = SettlementStatus.PENDING;
        Pageable pageable = PageRequest.of(0, 10);
        Page<Settlement> settlementPage = new PageImpl<>(List.of(testSettlement), pageable, 1);
        
        when(settlementRepository.findByStatus(status, pageable)).thenReturn(settlementPage);

        // When
        Page<SettlementResponse> result = settlementService.getSettlementsByStatus(status, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        verify(settlementRepository).findByStatus(status, pageable);
    }

    @Test
    @DisplayName("Should get settlements requiring retry")
    void shouldGetSettlementsRequiringRetry() {
        // Given
        testSettlement.updateStatus(SettlementStatus.FAILED, "Connection timeout");
        List<Settlement> settlements = List.of(testSettlement);
        when(settlementRepository.findSettlementsRequiringRetry(any(LocalDateTime.class))).thenReturn(settlements);

        // When
        List<SettlementResponse> result = settlementService.getSettlementsRequiringRetry();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(SettlementStatus.FAILED);
        verify(settlementRepository).findSettlementsRequiringRetry(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Should get overdue settlements")
    void shouldGetOverdueSettlements() {
        // Given
        testSettlement.setExpectedCompletion(LocalDateTime.now().minusHours(1));
        List<Settlement> settlements = List.of(testSettlement);
        when(settlementRepository.findOverdueSettlements(any(LocalDateTime.class))).thenReturn(settlements);

        // When
        List<SettlementResponse> result = settlementService.getOverdueSettlements();

        // Then
        assertThat(result).hasSize(1);
        verify(settlementRepository).findOverdueSettlements(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Should get settlements requiring manual intervention")
    void shouldGetSettlementsRequiringManualIntervention() {
        // Given
        testSettlement.updateStatus(SettlementStatus.MANUAL_REVIEW, "Requires compliance review");
        List<Settlement> settlements = List.of(testSettlement);
        when(settlementRepository.findSettlementsRequiringManualIntervention()).thenReturn(settlements);

        // When
        List<SettlementResponse> result = settlementService.getSettlementsRequiringManualIntervention();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(SettlementStatus.MANUAL_REVIEW);
        verify(settlementRepository).findSettlementsRequiringManualIntervention();
    }

    @Test
    @DisplayName("Should retry failed settlement")
    void shouldRetryFailedSettlement() {
        // Given
        testSettlement.updateStatus(SettlementStatus.FAILED, "Network error");
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));
        when(settlementRepository.save(any(Settlement.class))).thenReturn(testSettlement);

        // When
        SettlementResponse result = settlementService.retrySettlement(testSettlement.getId(), "Manual retry");

        // Then
        assertThat(result).isNotNull();
        verify(settlementRepository).save(any(Settlement.class));
        verify(auditService).logEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception when retrying settlement that cannot be retried")
    void shouldThrowExceptionWhenRetryingSettlementThatCannotBeRetried() {
        // Given
        testSettlement.updateStatus(SettlementStatus.COMPLETED, null);
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));

        // When & Then
        assertThatThrownBy(() -> settlementService.retrySettlement(testSettlement.getId(), "Manual retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be retried");
    }

    @Test
    @DisplayName("Should cancel pending settlement")
    void shouldCancelPendingSettlement() {
        // Given
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));
        when(settlementRepository.save(any(Settlement.class))).thenReturn(testSettlement);

        // When
        SettlementResponse result = settlementService.cancelSettlement(testSettlement.getId(), "User requested");

        // Then
        assertThat(result).isNotNull();
        verify(settlementRepository).save(any(Settlement.class));
        verify(auditService).logEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception when cancelling settlement in final status")
    void shouldThrowExceptionWhenCancellingSettlementInFinalStatus() {
        // Given
        testSettlement.updateStatus(SettlementStatus.COMPLETED, null);
        when(settlementRepository.findById(testSettlement.getId())).thenReturn(Optional.of(testSettlement));

        // When & Then
        assertThatThrownBy(() -> settlementService.cancelSettlement(testSettlement.getId(), "User requested"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel settlement in final status");
    }

    @Test
    @DisplayName("Should update settlement status")
    void shouldUpdateSettlementStatus() {
        // Given
        String reference = testSettlement.getSettlementReference();
        when(settlementRepository.findBySettlementReference(reference)).thenReturn(Optional.of(testSettlement));
        when(settlementRepository.save(any(Settlement.class))).thenReturn(testSettlement);

        // When
        SettlementResponse result = settlementService.updateSettlementStatus(
                reference, SettlementStatus.PROCESSING, null, null);

        // Then
        assertThat(result).isNotNull();
        verify(settlementRepository).save(any(Settlement.class));
        verify(auditService).logEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception for invalid status transition")
    void shouldThrowExceptionForInvalidStatusTransition() {
        // Given
        testSettlement.updateStatus(SettlementStatus.COMPLETED, null);
        String reference = testSettlement.getSettlementReference();
        when(settlementRepository.findBySettlementReference(reference)).thenReturn(Optional.of(testSettlement));

        // When & Then
        assertThatThrownBy(() -> settlementService.updateSettlementStatus(
                reference, SettlementStatus.INITIATED, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("Should process reconciliation")
    void shouldProcessReconciliation() {
        // Given
        testSettlement.updateStatus(SettlementStatus.COMPLETED, null);
        List<Settlement> settlements = List.of(testSettlement);
        when(settlementRepository.findSettlementsPendingReconciliation()).thenReturn(settlements);
        when(settlementRepository.save(any(Settlement.class))).thenReturn(testSettlement);

        // When
        settlementService.processReconciliation();

        // Then
        verify(settlementRepository).findSettlementsPendingReconciliation();
        verify(settlementRepository, atLeast(2)).save(any(Settlement.class));
    }

    @Test
    @DisplayName("Should validate settlement request")
    void shouldValidateSettlementRequest() {
        // Given
        SettlementRequest invalidRequest = SettlementRequest.builder()
                .transactionId(null) // Invalid
                .paymentProcessor(PaymentProcessor.SWIFT)
                .settlementAmount(BigDecimal.valueOf(-100)) // Invalid
                .settlementCurrency("INVALID") // Invalid
                .build();

        // When & Then
        assertThatThrownBy(() -> invalidRequest.validate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should create SWIFT settlement request")
    void shouldCreateSwiftSettlementRequest() {
        // When
        SettlementRequest request = SettlementRequest.createSwiftSettlement(
                UUID.randomUUID(),
                BigDecimal.valueOf(1000),
                "USD",
                "123456789",
                "TESTBKXXXX",
                "John Doe"
        );

        // Then
        assertThat(request).isNotNull();
        assertThat(request.getPaymentProcessor()).isEqualTo(PaymentProcessor.SWIFT);
        assertThat(request.getBeneficiaryAccountNumber()).isEqualTo("123456789");
        assertThat(request.getBeneficiaryBankCode()).isEqualTo("TESTBKXXXX");
        assertThat(request.getBeneficiaryName()).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("Should create card settlement request")
    void shouldCreateCardSettlementRequest() {
        // When
        SettlementRequest request = SettlementRequest.createCardSettlement(
                UUID.randomUUID(),
                PaymentProcessor.VISA_DIRECT,
                BigDecimal.valueOf(500),
                "USD",
                "4111111111111111",
                "John Doe"
        );

        // Then
        assertThat(request).isNotNull();
        assertThat(request.getPaymentProcessor()).isEqualTo(PaymentProcessor.VISA_DIRECT);
        assertThat(request.getBeneficiaryAccountNumber()).isEqualTo("4111111111111111");
        assertThat(request.getBeneficiaryName()).isEqualTo("John Doe");
        assertThat(request.isPrioritySettlement()).isTrue();
    }
}
