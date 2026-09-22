package com.aegisx.integration.razorpay.controller;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.integration.razorpay.service.RazorpaySignatureService;
import com.aegisx.integration.razorpay.service.RazorpayWebhookDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RazorpayWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RazorpaySignatureService razorpaySignatureService;

    @MockBean
    private RazorpayWebhookDispatcher razorpayWebhookDispatcher;

    @Test
    @DisplayName("Webhook endpoint returns 401 when signature header is missing or invalid")
    void testMissingOrInvalidSignatureReturnsUnauthorized() throws Exception {
        String payload = "{\"event\":\"payment.captured\"}";

        doThrow(new InvalidWebhookSignatureException("X-Razorpay-Signature header is missing"))
                .when(razorpaySignatureService).verifySignature(any(), isNull());

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("X-Razorpay-Signature header is missing"));

        verify(razorpayWebhookDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    @DisplayName("Webhook endpoint returns 200 OK when signature verification succeeds and passes eventId header")
    void testValidWebhookReturnsOk() throws Exception {
        String payload = "{\"event\":\"payment.captured\",\"payload\":{}}";
        String validSignature = "mock_valid_signature_12345";
        String eventIdHeader = "evt_delivery_header_123";

        when(razorpaySignatureService.verifySignature(any(), eq(validSignature))).thenReturn(true);
        doNothing().when(razorpayWebhookDispatcher).dispatch(any(), any(), any());

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .header("X-Razorpay-Signature", validSignature)
                        .header("X-Razorpay-Event-Id", eventIdHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(razorpayWebhookDispatcher).dispatch(eq(payload), eq(eventIdHeader), any());
    }
}

