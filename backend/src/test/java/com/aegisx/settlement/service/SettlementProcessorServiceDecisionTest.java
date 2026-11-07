package com.aegisx.settlement.service;

import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SettlementProcessorServiceDecisionTest {

    @Mock private RazorpayXClient razorpayXClient;
    @Mock private Environment environment;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private SettlementProcessorService processorService;

    @ParameterizedTest
    @EnumSource(value = TransactionStatus.class, names = {"COMPLIANCE_HOLD", "REJECTED"})
    void executionModuleRejectsEscalatedAndBlockedTransactions(TransactionStatus status) {
        Settlement settlement = Settlement.builder()
                .transaction(Transaction.builder().status(status).build())
                .paymentProcessor(PaymentProcessor.RAZORPAYX)
                .status(SettlementStatus.INITIATED)
                .settlementAmount(new BigDecimal("10.00"))
                .settlementCurrency("INR")
                .build();

        assertThatThrownBy(() -> processorService.submitSettlement(settlement))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an approved transaction");
        verifyNoInteractions(razorpayXClient);
    }
}
