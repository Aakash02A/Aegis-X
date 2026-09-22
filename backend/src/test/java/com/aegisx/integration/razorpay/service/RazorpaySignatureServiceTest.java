package com.aegisx.integration.razorpay.service;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.integration.razorpay.config.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RazorpaySignatureServiceTest {

    private RazorpayProperties properties;
    private RazorpaySignatureService signatureService;
    private final String secret = "test_webhook_secret_key_12345";

    @BeforeEach
    void setUp() {
        properties = new RazorpayProperties();
        properties.setWebhookSecret(secret);
        signatureService = new RazorpaySignatureService(properties);
    }

    @Test
    @DisplayName("Valid signature passes HMAC verification")
    void testValidSignature() {
        String payload = "{\"event\":\"payment.captured\",\"payment\":{\"id\":\"pay_123\"}}";
        String validSignature = signatureService.calculateHmacSha256(payload, secret);

        assertTrue(signatureService.verifySignature(payload, validSignature));
    }

    @Test
    @DisplayName("Invalid signature throws InvalidWebhookSignatureException")
    void testInvalidSignatureThrowsException() {
        String payload = "{\"event\":\"payment.captured\"}";
        String invalidSignature = "invalid_hex_signature_string_000000";

        assertThrows(InvalidWebhookSignatureException.class, () ->
                signatureService.verifySignature(payload, invalidSignature));
    }

    @Test
    @DisplayName("Raw body mutation (whitespace/character altered) invalidates signature")
    void testRawBodyMutationFailsVerification() {
        String originalPayload = "{\"event\":\"payment.captured\"}";
        String validSignature = signatureService.calculateHmacSha256(originalPayload, secret);

        // Mutated with extra space
        String mutatedPayload = "{\"event\": \"payment.captured\"}";

        assertThrows(InvalidWebhookSignatureException.class, () ->
                signatureService.verifySignature(mutatedPayload, validSignature));
    }

    @Test
    @DisplayName("Missing or null signature header throws InvalidWebhookSignatureException")
    void testMissingSignatureThrowsException() {
        String payload = "{\"event\":\"payment.captured\"}";

        assertThrows(InvalidWebhookSignatureException.class, () ->
                signatureService.verifySignature(payload, null));

        assertThrows(InvalidWebhookSignatureException.class, () ->
                signatureService.verifySignature(payload, "   "));
    }
}
