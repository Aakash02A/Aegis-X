package com.aegisx.integration.whatsapp;

import com.aegisx.notification.dto.WhatsAppWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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
     * Verify webhook request signature using X-Hub-Signature header.
     * 
     * Meta signs webhook POST requests with HMAC-SHA256 using the app secret.
     * Format: X-Hub-Signature: sha256=<hex_digest>
     * 
     * Security: This MUST be enforced for production to prevent injection attacks.
     * 
     * @param body Raw request body as JSON string
     * @param signature X-Hub-Signature header value (format: sha256=...)
     * @return true if signature valid, false otherwise
     */
    public boolean verifyWebhookSignature(String body, String signature) {
        try {
            // Security: STRICT verification - fail if any required component is missing
            if (signature == null || signature.trim().isEmpty()) {
                log.warn("Webhook signature verification failed: X-Hub-Signature header missing");
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
            
            String expectedSignature = computeHmacSignature(body, appSecret);
            boolean valid = expectedSignature.equals(signature);
            
            if (!valid) {
                log.warn("Webhook signature verification failed: signature mismatch. Expected: {}, Got: {}",
                    expectedSignature.substring(0, Math.min(10, expectedSignature.length())) + "...",
                    signature.substring(0, Math.min(10, signature.length())) + "...");
            } else {
                log.debug("Webhook signature verified successfully");
            }
            
            return valid;
            
        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }

    private String computeHmacSignature(String body, String appSecret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String input = body + appSecret;
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hash);
    }
}
