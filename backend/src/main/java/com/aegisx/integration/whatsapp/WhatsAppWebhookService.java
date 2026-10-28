package com.aegisx.integration.whatsapp;

import com.aegisx.notification.dto.WhatsAppWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * WhatsAppWebhookService handles Meta WhatsApp webhook verification and processing.
 * 
 * Responsibilities:
 * - Verify webhook token (security boundary)
 * - Validate request signature
 * - Idempotency checks for webhook events
 * - Classify webhook event type
 * 
 * Design:
 * - Always verify webhook token before processing
 * - Webhook events can be replayed; detect duplicates via message_id
 * - Never process unverified payloads
 * - Log all verification failures for security audit
 */
@Slf4j
@Component
public class WhatsAppWebhookService {
    
    @Value("${aegisx.whatsapp.webhook-verify-token}")
    private String webhookVerifyToken;

    @Value("${aegisx.whatsapp.app-secret}")
    private String appSecret;
    
    /**
     * Verify webhook token from Meta.
     * Called on GET /api/webhooks/whatsapp during webhook setup.
     * 
     * @param token Token from Meta
     * @return true if token matches configured verify token
     */
    public boolean verifyWebhookToken(String token) {
        if (token == null || webhookVerifyToken == null) {
            log.warn("Webhook token verification failed: token is null");
            return false;
        }
        
        boolean valid = token.equals(webhookVerifyToken);
        
        if (!valid) {
            log.warn("Webhook token verification failed: token mismatch");
        } else {
            log.debug("Webhook token verified successfully");
        }
        
        return valid;
    }
    
    /**
     * Classify webhook event type.
     * 
     * @param payload Webhook payload
     * @return Event type: MESSAGE, DELIVERY_RECEIPT, or UNKNOWN
     */
    public String classifyEventType(WhatsAppWebhookPayload payload) {
        if (payload == null || payload.getEntries() == null || payload.getEntries().isEmpty()) {
            return "UNKNOWN";
        }
        
        for (WhatsAppWebhookPayload.Entry entry : payload.getEntries()) {
            if (entry.getChanges() == null) continue;
            
            for (WhatsAppWebhookPayload.Change change : entry.getChanges()) {
                if (change.getValue() == null) continue;
                
                if (change.getValue().getMessages() != null && !change.getValue().getMessages().isEmpty()) {
                    return "MESSAGE";
                }
                
                if (change.getValue().getStatuses() != null && !change.getValue().getStatuses().isEmpty()) {
                    return "DELIVERY_RECEIPT";
                }
            }
        }
        
        return "UNKNOWN";
    }
    
    /**
     * Extract phone number from webhook.
     * 
     * @param payload Webhook payload
     * @return Sender phone number or null
     */
    public String extractSenderPhone(WhatsAppWebhookPayload payload) {
        if (payload == null || payload.getEntries() == null || payload.getEntries().isEmpty()) {
            return null;
        }
        
        for (WhatsAppWebhookPayload.Entry entry : payload.getEntries()) {
            if (entry.getChanges() == null) continue;
            
            for (WhatsAppWebhookPayload.Change change : entry.getChanges()) {
                if (change.getValue() == null) continue;
                
                if (change.getValue().getMessages() != null && !change.getValue().getMessages().isEmpty()) {
                    return change.getValue().getMessages().get(0).getFrom();
                }
                
                if (change.getValue().getStatuses() != null && !change.getValue().getStatuses().isEmpty()) {
                    return change.getValue().getStatuses().get(0).getRecipientId();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extract message ID from webhook.
     * Used for idempotency checks.
     * 
     * @param payload Webhook payload
     * @return Message ID or null
     */
    public String extractMessageId(WhatsAppWebhookPayload payload) {
        if (payload == null || payload.getEntries() == null || payload.getEntries().isEmpty()) {
            return null;
        }
        
        for (WhatsAppWebhookPayload.Entry entry : payload.getEntries()) {
            if (entry.getChanges() == null) continue;
            
            for (WhatsAppWebhookPayload.Change change : entry.getChanges()) {
                if (change.getValue() == null) continue;
                
                if (change.getValue().getMessages() != null && !change.getValue().getMessages().isEmpty()) {
                    return change.getValue().getMessages().get(0).getId();
                }
                
                if (change.getValue().getStatuses() != null && !change.getValue().getStatuses().isEmpty()) {
                    return change.getValue().getStatuses().get(0).getId();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Verify a WhatsApp webhook request signature against the raw request body.
     *
     * <p>Meta sends {@code X-Hub-Signature-256: sha256=<hex>} on current Cloud API webhooks, and the
     * digest is <strong>HMAC-SHA256 keyed with the app secret over the raw body bytes</strong> — not
     * a plain SHA-256 of the body concatenated with the secret. The legacy {@code X-Hub-Signature}
     * header carries {@code sha1=<hex>}, which is HMAC-SHA1 under the same key, so both schemes are
     * accepted and each is verified with the algorithm its prefix names. An unknown prefix is
     * rejected rather than guessed at.
     *
     * <p>The body must be the exact bytes received: parsing and re-serialising JSON changes the
     * bytes and breaks the digest.
     *
     * @param body raw request body
     * @param signature value of {@code X-Hub-Signature-256} (preferred) or {@code X-Hub-Signature}
     * @return true only if the signature is present, well-formed and matches
     */
    public boolean verifyWebhookSignature(String body, String signature) {
        try {
            // Security: STRICT verification - fail if any required component is missing
            if (signature == null || signature.trim().isEmpty()) {
                log.warn("Webhook signature verification failed: signature header missing");
                return false;
            }

            if (appSecret == null || appSecret.trim().isEmpty()) {
                log.error("Webhook signature verification failed: app secret not configured");
                return false;
            }

            if (body == null) {
                log.warn("Webhook signature verification failed: request body is null");
                return false;
            }

            int separator = signature.indexOf('=');
            if (separator <= 0) {
                log.warn("Webhook signature verification failed: missing algorithm prefix");
                return false;
            }

            String algorithm = signature.substring(0, separator).toLowerCase(Locale.ROOT);
            String macAlgorithm = switch (algorithm) {
                case "sha256" -> "HmacSHA256";
                case "sha1" -> "HmacSHA1";
                default -> null;
            };
            if (macAlgorithm == null) {
                log.warn("Webhook signature verification failed: unsupported algorithm '{}'", algorithm);
                return false;
            }

            byte[] expected = hmac(macAlgorithm, body, appSecret);
            byte[] provided = HexFormat.of().parseHex(signature.substring(separator + 1).trim());

            boolean valid = MessageDigest.isEqual(expected, provided);
            if (!valid) {
                log.warn("Webhook signature verification failed: {} mismatch", algorithm);
            } else {
                log.debug("Webhook signature verified successfully ({})", algorithm);
            }
            return valid;

        } catch (IllegalArgumentException e) {
            log.warn("Webhook signature verification failed: signature is not valid hex");
            return false;
        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }

    private byte[] hmac(String algorithm, String body, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    }
}
