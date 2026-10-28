package com.aegisx.integrity.webhook;

import com.aegisx.common.exception.InvalidWebhookSignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Webhook signature verification for the integrity module.
 *
 * <p>The provider documents the algorithm as <strong>HMAC-SHA256 over the raw request body</strong>
 * keyed by the webhook secret, delivered in the {@code X-Razorpay-Signature} header, and it
 * explicitly warns that the body must not be parsed or re-serialised before verification. This
 * class therefore accepts the body as a {@link String} straight from the request and never touches
 * JSON.</p>
 *
 * <p>Comparison is constant-time to avoid leaking the expected digest through timing.</p>
 */
@Slf4j
@Service
public class IntegrityWebhookSignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String MISSING_SIGNATURE = "Webhook signature header is missing";

    /** Constant-time verification. Returns false rather than throwing, for use in adapters. */
    public boolean verify(String rawBody, String signature, String secret) {
        if (signature == null || signature.isBlank() || rawBody == null || secret == null || secret.isBlank()) {
            return false;
        }
        String expected = compute(rawBody, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies and fails loudly. Used by the ingestion path so an unsigned or tampered delivery can
     * never be persisted as if it were legitimate.
     *
     * @throws InvalidWebhookSignatureException when the secret is unconfigured or the digest differs
     */
    public void verifyOrThrow(String rawBody, String signature, String secret, String providerLabel) {
        if (signature == null || signature.isBlank()) {
            throw new InvalidWebhookSignatureException(MISSING_SIGNATURE);
        }
        if (secret == null || secret.isBlank()) {
            log.error("{} webhook secret is not configured; refusing to accept deliveries", providerLabel);
            throw new InvalidWebhookSignatureException(
                    providerLabel + " webhook secret is not configured; cannot verify authenticity");
        }
        if (!verify(rawBody, signature, secret)) {
            log.warn("Signature verification failed for an incoming {} webhook", providerLabel);
            throw new InvalidWebhookSignatureException("Invalid webhook signature");
        }
    }

    public String compute(String rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 webhook signature", e);
        }
    }

    /** SHA-256 hex digest, used to synthesise a stable event id when the provider omits one. */
    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
