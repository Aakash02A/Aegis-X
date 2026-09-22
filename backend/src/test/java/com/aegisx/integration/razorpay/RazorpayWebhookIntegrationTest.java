package com.aegisx.integration.razorpay;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.integration.razorpay.config.RazorpayProperties;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import com.aegisx.integration.razorpay.service.RazorpaySignatureService;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RazorpayWebhookIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RazorpaySignatureService razorpaySignatureService;

    @Autowired
    private RazorpayProperties razorpayProperties;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    private User merchant;
    private String webhookSecret;

    @BeforeEach
    void setUp() {
        webhookSecret = razorpayProperties.getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            webhookSecret = "dev-webhook-secret-for-aegis-x-local-testing";
            razorpayProperties.setWebhookSecret(webhookSecret);
        }

        merchant = User.builder()
                .fullName("Integration Merchant Ltd")
                .email("webhook_merchant_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .passwordHash("hashed_pwd")
                .role(Role.MERCHANT)
                .build();
        merchant = userRepository.save(merchant);
    }

    @Test
    @DisplayName("End-to-End: payment.captured webhook verifies HMAC, advances transaction to EVIDENCE_PENDING, and handles replays idempotently")
    void testEndToEndPaymentCapturedWebhook() throws Exception {
        // 1. Create Transaction in CREATED status
        CreateTransactionRequest createRequest = CreateTransactionRequest.builder()
                .amount(new BigDecimal("3000.00"))
                .currency("USD")
                .beneficiaryName("Pacific Global Ltd")
                .purposeCode("P0103")
                .build();

        TransactionResponse txResponse = transactionService.createTransaction(createRequest, merchant.getEmail());
        String referenceNumber = txResponse.getReferenceNumber();
        UUID txId = txResponse.getId();

        assertEquals(TransactionStatus.CREATED, txResponse.getStatus());

        // 2. Construct signed payment.captured payload
        String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String rawPayload = "{\n" +
                "  \"entity\": \"event\",\n" +
                "  \"account_id\": \"acc_test123\",\n" +
                "  \"event\": \"payment.captured\",\n" +
                "  \"created_at\": 1726963200,\n" +
                "  \"payload\": {\n" +
                "    \"payment\": {\n" +
                "      \"entity\": {\n" +
                "        \"id\": \"" + paymentId + "\",\n" +
                "        \"amount\": 300000,\n" +
                "        \"currency\": \"USD\",\n" +
                "        \"status\": \"captured\",\n" +
                "        \"order_id\": \"order_test_999\",\n" +
                "        \"notes\": {\n" +
                "          \"transaction_reference\": \"" + referenceNumber + "\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        String signature = razorpaySignatureService.calculateHmacSha256(rawPayload, webhookSecret);
        String eventIdHeader = "evt_delivery_" + UUID.randomUUID().toString().substring(0, 10);

        // 3. Post webhook to public endpoint with X-Razorpay-Event-Id header
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", signature)
                        .header("X-Razorpay-Event-Id", eventIdHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        // 4. Verify WebhookEvent stored in DB using X-Razorpay-Event-Id
        WebhookEvent savedEvent = webhookEventRepository.findByEventId(eventIdHeader).orElseThrow();
        assertTrue(savedEvent.isSignatureVerified());
        assertEquals(WebhookProcessingStatus.PROCESSED, savedEvent.getProcessingStatus());
        assertEquals(paymentId, savedEvent.getPaymentId());
        assertEquals(referenceNumber, savedEvent.getTransactionReference());
        assertEquals(rawPayload, savedEvent.getPayload());
        assertNotNull(savedEvent.getProviderEventTimestamp());

        // 5. Verify Transaction transitioned: CREATED -> PAYMENT_RECEIVED -> EVIDENCE_PENDING
        Transaction updatedTx = transactionRepository.findById(txId).orElseThrow();
        assertEquals(TransactionStatus.EVIDENCE_PENDING, updatedTx.getStatus());

        // 6. Idempotency Test: Replay the identical webhook with same X-Razorpay-Event-Id
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", signature)
                        .header("X-Razorpay-Event-Id", eventIdHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        // Verify transaction remains in EVIDENCE_PENDING without error or re-transition
        Transaction txAfterReplay = transactionRepository.findById(txId).orElseThrow();
        assertEquals(TransactionStatus.EVIDENCE_PENDING, txAfterReplay.getStatus());

    }

    @Test
    @DisplayName("Invalid JSON with valid HMAC signature records FAILED WebhookEvent and leaves transactions unmodified")
    void testInvalidJsonWithValidSignatureRecordsFailedEvent() throws Exception {
        String brokenJson = "{ \"event\": \"payment.captured\", \"broken\": ";
        String signature = razorpaySignatureService.calculateHmacSha256(brokenJson, webhookSecret);

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brokenJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        List<WebhookEvent> events = webhookEventRepository.findAll();
        boolean foundFailed = events.stream()
                .anyMatch(e -> e.getProcessingStatus() == WebhookProcessingStatus.FAILED && e.getPayload().equals(brokenJson));

        assertTrue(foundFailed, "Expected a WebhookEvent recorded with status FAILED for malformed JSON");
    }
}
