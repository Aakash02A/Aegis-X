package com.aegisx.settlement.service;

import com.aegisx.audit.service.AuditService;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceProcessorTest {

    @Mock private SettlementRepository settlementRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SettlementProcessorService settlementProcessorService;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private RazorpayXClient razorpayXClient;
    @Mock private EntityManager entityManager;

    @InjectMocks private SettlementService service;

    @Test
    void failedProcessorResultNeverWritesSubmitted() {
        UUID settlementId = UUID.randomUUID();
        Settlement settlement = Settlement.builder()
                .transaction(Transaction.builder().status(TransactionStatus.APPROVED).build())
                .settlementReference("STL-TEST-1")
                .paymentProcessor(PaymentProcessor.RAZORPAYX)
                .status(SettlementStatus.INITIATED)
                .settlementAmount(new BigDecimal("10.00"))
                .settlementCurrency("INR")
                .initiatedAt(LocalDateTime.now())
                .build();
        List<SettlementStatus> savedStatuses = new ArrayList<>();

        when(settlementRepository.findByIdForUpdate(settlementId)).thenReturn(Optional.of(settlement));
        when(settlementProcessorService.submitSettlement(settlement))
                .thenReturn(SettlementService.SettlementProcessorResult.builder()
                        .processorTransactionId("provider-id")
                        .initialStatus(SettlementStatus.SUBMITTED)
                        .success(false)
                        .errorMessage("Provider rejected request")
                        .build());
        doAnswer(invocation -> {
            Settlement saved = invocation.getArgument(0);
            savedStatuses.add(saved.getStatus());
            return saved;
        }).when(settlementRepository).save(any(Settlement.class));

        assertThatThrownBy(() -> service.processSettlement(settlementId))
                .isInstanceOf(SettlementService.SettlementProcessingException.class);

        assertThat(savedStatuses).containsExactly(SettlementStatus.PROCESSING, SettlementStatus.FAILED)
                .doesNotContain(SettlementStatus.SUBMITTED);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.FAILED);
        verify(entityManager).refresh(settlement.getTransaction(), LockModeType.PESSIMISTIC_WRITE);
    }
}
