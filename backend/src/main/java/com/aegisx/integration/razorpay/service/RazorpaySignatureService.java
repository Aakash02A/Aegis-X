package com.aegisx.integration.razorpay.service;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import com.aegisx.integration.razorpay.config.RazorpayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpaySignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final RazorpayProperties razorpayProperties;

    /**
     * Verifies the authenticity of an incoming Razorpay webhook payload against its signature.
     *
     * @param rawPayload The raw, unmodified request payload as received from the HTTP request.
     * @param signature  The signature string passed in the X-Razorpay-Signature header.
     * @return true if valid; throws InvalidWebhookSignatureException otherwise.
     */
    public boolean verifySignature(String rawPayload, String signature) {
        if (signature == null || signature.isBlank()) {
            log.warn("Webhook signature is missing or blank");
            throw new InvalidWebhookSignatureException("X-Razorpay-Signature header is missing");
        }
        if (rawPayload == null) {
            log.warn("Webhook payload body is null");
            throw new InvalidWebhookSignatureException("Webhook payload cannot be null");
        }

        String secret = razorpayProperties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("Razorpay webhook secret is not configured in application properties");
            throw new IllegalStateException("Razorpay webhook secret is not configured");
        }

        String computedSignature = calculateHmacSha256(rawPayload, secret);

        // Constant-time byte array comparison to prevent timing attacks
        byte[] expectedBytes = signature.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = computedSignature.getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            log.warn("HMAC SHA256 signature verification failed for incoming webhook");
            throw new InvalidWebhookSignatureException("Invalid webhook signature");
        }

        return true;
    }

    /**
     * Calculates HMAC-SHA256 hex digest.
     */
    public String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }
}
