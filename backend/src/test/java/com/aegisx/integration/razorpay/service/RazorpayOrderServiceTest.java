package com.aegisx.integration.razorpay.service;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.audit.service.AuditService;
import com.aegisx.integration.razorpay.config.RazorpayProperties;
import com.aegisx.integration.razorpay.dto.CreatePaymentOrderResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RazorpayOrderServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RazorpayOrderClient razorpayOrderClient;
    @Mock private AuditService auditService;

    private final RazorpayProperties properties = new RazorpayProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final String email = "merchant@example.test";
    private User merchant;
    private Transaction transaction;
    private RazorpayOrderService service;

    @BeforeEach
    void setUp() {
        properties.setKeyId("rzp_test_public");
        properties.setKeySecret("not-a-real-secret");
        service = new RazorpayOrderService(
                transactionRepository, userRepository, razorpayOrderClient, properties, auditService);
        merchant = User.builder().email(email).build();
        merchant.setId(merchantId);
        transaction = Transaction.builder()
                .referenceNumber("TXN-ORDER-001")
                .merchant(merchant)
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Test beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.CREATED)
                .build();
        transaction.setId(transactionId);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(merchant));
        when(transactionRepository.findByIdAndMerchantIdForUpdate(transactionId, merchantId))
                .thenReturn(Optional.of(transaction));
    }

    @Test
    void createsAndPersistsAnOrderBoundToTheOwnedTransaction() throws Exception {
        when(razorpayOrderClient.findOrdersByReceipt("TXN-ORDER-001"))
                .thenReturn(objectMapper.readTree("{\"items\":[]}"));
        when(razorpayOrderClient.createOrder(anyLong(), any(String.class), any(String.class), any()))
                .thenReturn(order("order_Aegis001", 250000, "USD", "TXN-ORDER-001"));

        CreatePaymentOrderResponse response = service.createOrder(transactionId, email);

        assertThat(response.orderId()).isEqualTo("order_Aegis001");
        assertThat(response.amount()).isEqualTo(250000);
        assertThat(transaction.getRazorpayOrderId()).isEqualTo("order_Aegis001");
        verify(transactionRepository).saveAndFlush(transaction);
    }

    @Test
    void recoversAnOrderAfterAnAmbiguousProviderResponse() throws Exception {
        when(razorpayOrderClient.findOrdersByReceipt("TXN-ORDER-001"))
                .thenReturn(objectMapper.readTree("{\"items\":[{\"id\":\"order_recovered\","
                        + "\"amount\":250000,\"currency\":\"USD\",\"receipt\":\"TXN-ORDER-001\"}]}"));

        CreatePaymentOrderResponse response = service.createOrder(transactionId, email);

        assertThat(response.orderId()).isEqualTo("order_recovered");
        verify(razorpayOrderClient, never()).createOrder(anyLong(), any(String.class), any(String.class), any());
        verify(transactionRepository).saveAndFlush(transaction);
    }

    @Test
    void rejectsAProviderOrderThatDoesNotMatchTheTransaction() throws Exception {
        when(razorpayOrderClient.findOrdersByReceipt("TXN-ORDER-001"))
                .thenReturn(objectMapper.readTree("{\"items\":[{\"id\":\"order_wrong_amount\","
                        + "\"amount\":10,\"currency\":\"USD\",\"receipt\":\"TXN-ORDER-001\"}]}"));

        assertThatThrownBy(() -> service.createOrder(transactionId, email))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    void doesNotCallProviderForAnotherMerchantsTransaction() {
        when(transactionRepository.findByIdAndMerchantIdForUpdate(transactionId, merchantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(transactionId, email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction not found");
        verify(razorpayOrderClient, never()).findOrdersByReceipt(any());
    }

    private com.fasterxml.jackson.databind.JsonNode order(
            String id, long amount, String currency, String receipt) throws Exception {
        return objectMapper.readTree("{\"id\":\"" + id + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"receipt\":\"" + receipt + "\"}");
    }
}
