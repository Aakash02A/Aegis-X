package com.aegisx.transaction.controller;

import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.service.UserService;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    private String email;
    private String token;

    @BeforeEach
    void setUp() {
        email = "merchant-" + UUID.randomUUID() + "@example.com";
        userService.register(RegisterRequest.builder()
                .fullName("Test Merchant")
                .email(email)
                .password("Password@123")
                .build());
        token = userService.login(LoginRequest.builder()
                .email(email)
                .password("Password@123")
                .build()).getToken();
    }

    @Test
    void createTransactionReturnsCreatedWhenValid() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("1500.00", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.referenceNumber").isNotEmpty())
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void createTransactionReturnsBadRequestWhenInvalidValidation() throws Exception {
        CreateTransactionRequest request = CreateTransactionRequest.builder()
                .amount(new BigDecimal("-100.00"))
                .currency("US")
                .beneficiaryName("")
                .purposeCode("")
                .build();

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void listTransactionsReturnsPaginatedResults() throws Exception {
        createTransaction();

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.content[0].currency").value("USD"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getTransactionReturnsTransactionWhenFound() throws Exception {
        String id = createTransaction();

        mockMvc.perform(get("/api/transactions/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void getTransactionReturnsNotFoundWhenMissing() throws Exception {
        String id = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/transactions/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void merchantCannotReadAnotherMerchantsTransaction() throws Exception {
        String otherEmail = "other-merchant-" + UUID.randomUUID() + "@example.com";
        userService.register(RegisterRequest.builder()
                .fullName("Other Test Merchant")
                .email(otherEmail)
                .password("Password@123")
                .build());
        String otherToken = userService.login(LoginRequest.builder()
                .email(otherEmail)
                .password("Password@123")
                .build()).getToken();
        String otherTransactionId = createTransaction(otherToken);

        mockMvc.perform(get("/api/transactions/{id}", otherTransactionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/transactions/{id}", otherTransactionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(otherTransactionId));
    }

    @Test
    void unauthenticatedAccessReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());
    }

    private String createTransaction() throws Exception {
        return createTransaction(token);
    }

    private String createTransaction(String accessToken) throws Exception {
        String response = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("2000.00", "USD"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private static CreateTransactionRequest createRequest(String amount, String currency) {
        return CreateTransactionRequest.builder()
                .amount(new BigDecimal(amount))
                .currency(currency)
                .beneficiaryName("XYZ Logistics")
                .purposeCode("P0103")
                .build();
    }
}
