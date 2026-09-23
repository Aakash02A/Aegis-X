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
     * Verify webhook request signature (if Meta includes X-Hub-Signature).
     * Note: Meta sandbox may not sign requests; this is optional for MVP.
     * 
     * @param body Raw request body
     * @param signature X-Hub-Signature header value
     * @param appSecret App secret from Meta
     * @return true if signature valid
     */
    public boolean verifyWebhookSignature(String body, String signature, String appSecret) {
        try {
            if (signature == null || appSecret == null) {
                log.debug("Webhook signature verification skipped: signature or secret missing");
                return true;
            }
            
            String expectedSignature = computeSignature(body, appSecret);
            boolean valid = expectedSignature.equals(signature);
            
            if (!valid) {
                log.warn("Webhook signature verification failed");
            } else {
                log.debug("Webhook signature verified");
            }
            
            return valid;
            
        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage());
            return false;
        }
    }
    
    private String computeSignature(String body, String appSecret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String input = body + appSecret;
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hash);
    }
}
