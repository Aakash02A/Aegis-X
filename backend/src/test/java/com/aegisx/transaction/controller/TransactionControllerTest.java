package com.aegisx.transaction.controller;

import com.aegisx.auth.entity.Role;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @Test
    @WithMockUser(username = "merchant@example.com", roles = {"MERCHANT"})
    void createTransactionReturnsCreatedWhenValid() throws Exception {
        CreateTransactionRequest request = CreateTransactionRequest.builder()
                .amount(new BigDecimal("1500.00"))
                .currency("USD")
                .beneficiaryName("XYZ Logistics")
                .purposeCode("P0103")
                .build();

        UUID txId = UUID.randomUUID();
        TransactionResponse response = TransactionResponse.builder()
                .id(txId)
                .referenceNumber("TXN-20260921-A1B2C3")
                .merchantId(UUID.randomUUID())
                .amount(new BigDecimal("1500.00"))
                .currency("USD")
                .beneficiaryName("XYZ Logistics")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .createdAt(Instant.now())
                .build();

        when(transactionService.createTransaction(any(CreateTransactionRequest.class), eq("merchant@example.com")))
                .thenReturn(response);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(txId.toString()))
                .andExpect(jsonPath("$.referenceNumber").value("TXN-20260921-A1B2C3"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    @WithMockUser(username = "merchant@example.com", roles = {"MERCHANT"})
    void createTransactionReturnsBadRequestWhenInvalidValidation() throws Exception {
        CreateTransactionRequest request = CreateTransactionRequest.builder()
                .amount(new BigDecimal("-100.00")) // Negative amount
                .currency("US") // Invalid currency length
                .beneficiaryName("")
                .purposeCode("")
                .build();

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @WithMockUser(username = "merchant@example.com", roles = {"MERCHANT"})
    void listTransactionsReturnsPaginatedResults() throws Exception {
        UUID txId = UUID.randomUUID();
        TransactionResponse response = TransactionResponse.builder()
                .id(txId)
                .referenceNumber("TXN-20260921-A1B2C3")
                .merchantId(UUID.randomUUID())
                .amount(new BigDecimal("2000.00"))
                .currency("USD")
                .beneficiaryName("Supplier Inc")
                .purposeCode("P0103")
                .status(TransactionStatus.CREATED)
                .createdAt(Instant.now())
                .build();

        when(transactionService.listTransactions(eq("merchant@example.com"), eq(Role.MERCHANT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.singletonList(response)));

        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(txId.toString()))
                .andExpect(jsonPath("$.content[0].referenceNumber").value("TXN-20260921-A1B2C3"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(username = "merchant@example.com", roles = {"MERCHANT"})
    void getTransactionReturnsTransactionWhenFound() throws Exception {
        UUID txId = UUID.randomUUID();
        TransactionResponse response = TransactionResponse.builder()
                .id(txId)
                .referenceNumber("TXN-20260921-A1B2C3")
                .amount(new BigDecimal("3000.00"))
                .currency("EUR")
                .beneficiaryName("Euro Trading")
                .purposeCode("P0108")
                .status(TransactionStatus.CREATED)
                .createdAt(Instant.now())
                .build();

        when(transactionService.getTransaction(eq(txId), eq("merchant@example.com"), eq(Role.MERCHANT)))
                .thenReturn(response);

        mockMvc.perform(get("/api/transactions/{id}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId.toString()))
                .andExpect(jsonPath("$.referenceNumber").value("TXN-20260921-A1B2C3"))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    @WithMockUser(username = "merchant@example.com", roles = {"MERCHANT"})
    void getTransactionReturnsNotFoundWhenMissing() throws Exception {
        UUID txId = UUID.randomUUID();

        when(transactionService.getTransaction(eq(txId), eq("merchant@example.com"), eq(Role.MERCHANT)))
                .thenThrow(new TransactionNotFoundException(txId));

        mockMvc.perform(get("/api/transactions/{id}", txId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Transaction not found with ID: " + txId));
    }

    @Test
    void unauthenticatedAccessReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());
    }
}
