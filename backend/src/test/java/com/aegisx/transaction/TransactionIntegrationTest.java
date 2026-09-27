package com.aegisx.transaction;

import com.aegisx.auth.dto.AuthResponse;
import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.service.UserService;
import com.aegisx.common.exception.IllegalTransactionStateException;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    private String jwtToken;
    private String merchantEmail;

    @BeforeEach
    void setUp() {
        merchantEmail = "merchant_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        RegisterRequest registerRequest = RegisterRequest.builder()
                .fullName("Demo Merchant Ltd")
                .email(merchantEmail)
                .password("Password@123")
                .build();
        userService.register(registerRequest);

        LoginRequest loginRequest = LoginRequest.builder()
                .email(merchantEmail)
                .password("Password@123")
                .build();
        AuthResponse authResponse = userService.login(loginRequest);
        jwtToken = authResponse.getToken();
    }

    @Test
    @DisplayName("End-to-End: Register -> Login -> Create Transaction -> Query -> Verify Isolation")
    void testEndToEndTransactionWorkflow() throws Exception {
        // 1. Create Transaction
        CreateTransactionRequest createRequest = CreateTransactionRequest.builder()
                .amount(new BigDecimal("7500.00"))
                .currency("usd")
                .beneficiaryName("Pacific Coast Trading")
                .purposeCode("p0103")
                .build();

        MvcResult createResult = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.referenceNumber").exists())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.purposeCode").value("P0103"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();

        TransactionResponse createdTx = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                TransactionResponse.class
        );
        UUID transactionId = createdTx.getId();

        // 2. Fetch single transaction by ID
        mockMvc.perform(get("/api/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId.toString()))
                .andExpect(jsonPath("$.referenceNumber").value(createdTx.getReferenceNumber()))
                .andExpect(jsonPath("$.amount").value(7500.00));

        // 3. List transactions
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));

        // 4. Isolation Check: Register a second merchant who should not see first merchant's transaction
        String otherEmail = "other_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        userService.register(RegisterRequest.builder()
                .fullName("Other Merchant")
                .email(otherEmail)
                .password("Password@123")
                .build());
        AuthResponse otherAuth = userService.login(LoginRequest.builder().email(otherEmail).password("Password@123").build());

        // Other merchant querying first merchant's transaction gets 404 Not Found
        mockMvc.perform(get("/api/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + otherAuth.getToken()))
                .andExpect(status().isNotFound());

        // Other merchant list should be empty
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + otherAuth.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("End-to-End Lifecycle State Machine Transition Flow & Terminal State Enforcement")
    void testLifecycleTransitionsAndTerminalEnforcement() {
        // Create initial transaction
        CreateTransactionRequest createRequest = CreateTransactionRequest.builder()
                .amount(new BigDecimal("10000.00"))
                .currency("USD")
                .beneficiaryName("Apex Electronics")
                .purposeCode("P0103")
                .build();

        TransactionResponse response = transactionService.createTransaction(createRequest, merchantEmail);
        UUID txId = response.getId();
        assertEquals(TransactionStatus.CREATED, response.getStatus());

        // Step 1: PAYMENT_RECEIVED (simulating Razorpay webhook)
        response = transactionService.transitionStatus(txId, TransactionStatus.PAYMENT_RECEIVED, "RAZORPAY_WEBHOOK", "Payment captured");
        assertEquals(TransactionStatus.PAYMENT_RECEIVED, response.getStatus());

        // Step 2: EVIDENCE_PENDING (awaiting document upload)
        response = transactionService.transitionStatus(txId, TransactionStatus.EVIDENCE_PENDING, "SYSTEM", "Requesting invoice");
        assertEquals(TransactionStatus.EVIDENCE_PENDING, response.getStatus());

        // Step 3: OCR_PROCESSING (invoice uploaded)
        response = transactionService.transitionStatus(txId, TransactionStatus.OCR_PROCESSING, "DOCUMENT_SERVICE", "Invoice received");
        assertEquals(TransactionStatus.OCR_PROCESSING, response.getStatus());

        // Step 4: AI_ANALYSIS (OCR completed)
        response = transactionService.transitionStatus(txId, TransactionStatus.AI_ANALYSIS, "OCR_PIPELINE", "Text extracted");
        assertEquals(TransactionStatus.AI_ANALYSIS, response.getStatus());

        // Step 5: POLICY_EVALUATION (AI structured extraction completed)
        response = transactionService.transitionStatus(txId, TransactionStatus.POLICY_EVALUATION, "SPRING_AI", "Reasoning generated");
        assertEquals(TransactionStatus.POLICY_EVALUATION, response.getStatus());

        // Step 6: APPROVED (Policy Engine deterministic rule evaluation passed)
        response = transactionService.transitionStatus(txId, TransactionStatus.APPROVED, "POLICY_ENGINE", "Rules PR-01 to PR-08 evaluated OK");
        assertEquals(TransactionStatus.APPROVED, response.getStatus());

        // Terminal enforcement: APPROVED cannot transition to any other state
        assertThrows(IllegalTransactionStateException.class, () ->
                transactionService.transitionStatus(txId, TransactionStatus.PAYMENT_RECEIVED, "ATTACKER", "Rollback attempt"));
    }
}
