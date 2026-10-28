package com.aegisx.integration.razorpay.controller;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.integration.razorpay.repository.WebhookEventRepository;
import com.aegisx.integration.razorpay.service.RazorpaySignatureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RazorpayWebhookControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RazorpaySignatureService signatureService;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Test
    @DisplayName("Rejects missing and invalid signatures before event ingestion")
    void missingOrInvalidSignatureReturnsUnauthorized() throws Exception {
        String payload = "{\"event\":\"refund.created\",\"payload\":{}}";

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("X-Razorpay-Signature header is missing"));

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", "invalid-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"));

        assertThat(webhookEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("Verifies a genuine local HMAC and persists a supported webhook delivery")
    void validWebhookIsPersisted() throws Exception {
        String payload = "{\"event\":\"refund.created\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_local\"}}}}";
        String eventId = "evt_local_controller";
        String signature = signatureService.calculateHmacSha256(payload, "offline-test-webhook-secret");

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", signature)
                        .header("X-Razorpay-Event-Id", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        var stored = webhookEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(stored.isSignatureVerified()).isTrue();
        assertThat(stored.getEventType()).isEqualTo("refund.created");
        assertThat(stored.getProcessingStatus()).isEqualTo(WebhookProcessingStatus.IGNORED);
    }
}
