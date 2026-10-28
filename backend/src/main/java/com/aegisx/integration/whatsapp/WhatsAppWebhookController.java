package com.aegisx.integration.whatsapp;

import com.aegisx.notification.dto.WhatsAppWebhookPayload;
import com.aegisx.notification.service.InboundMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * WhatsAppWebhookController handles Meta WhatsApp webhook events.
 * 
 * Endpoints:
 * - GET /api/webhooks/whatsapp: Webhook verification (called by Meta during setup)
 * - POST /api/webhooks/whatsapp: Webhook events (messages, delivery receipts)
 * 
 * Security:
 * - Verify webhook token on every request
 * - Only process verified requests
 * - Idempotency via message ID
 * - All events logged for audit trail
 * 
 * Note: This is a public endpoint; no auth required for webhook events.
 * Meta will only send events to registered webhook URL with correct token.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/whatsapp")
@RequiredArgsConstructor
public class WhatsAppWebhookController {
    
    private final WhatsAppWebhookService webhookService;
    private final InboundMessageService inboundMessageService;
    
    /**
     * Webhook verification endpoint (GET).
     * 
     * Called by Meta to verify webhook URL during setup.
     * Returns challenge parameter if token is valid.
     * 
     * @param verifyToken Token from Meta
     * @param challenge Challenge string from Meta
     * @return Challenge if verified, 401 if not
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
        @RequestParam(name = "hub.verify_token") String verifyToken,
        @RequestParam(name = "hub.challenge") String challenge
    ) {
        log.info("Webhook verification request received");
        
        if (webhookService.verifyWebhookToken(verifyToken)) {
            log.info("Webhook verification successful");
            return ResponseEntity.ok(challenge);
        } else {
            log.warn("Webhook verification failed: invalid token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
    
    /**
     * Webhook event endpoint (POST).
     * 
     * Receives messages, delivery receipts, read receipts from Meta.
     * All POST requests MUST be verified via the signature header.
     *
     * <p>Meta's current Cloud API webhooks carry {@code X-Hub-Signature-256} (HMAC-SHA256). The
     * legacy {@code X-Hub-Signature} (HMAC-SHA1) is still accepted as a fallback, because older
     * apps and some proxies send only that one. When both are present the stronger header wins.
     *
     * @param rawPayload raw webhook payload, exactly as received
     * @param xHubSignature256 X-Hub-Signature-256 header (preferred)
     * @param xHubSignature X-Hub-Signature header (legacy fallback)
     * @param request Raw request for signature verification
     * @return 200 OK if processed successfully
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhookEvent(
        @RequestBody String rawPayload,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String xHubSignature256,
        @RequestHeader(value = "X-Hub-Signature", required = false) String xHubSignature,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        log.info("Webhook event received");
        
        try {
            String signature = (xHubSignature256 != null && !xHubSignature256.isBlank())
                ? xHubSignature256
                : xHubSignature;

            // Security: Verify webhook signature (REQUIRED)
            if (!webhookService.verifyWebhookSignature(rawPayload, signature)) {
                log.error("Webhook signature verification failed - rejecting request");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            // Parse payload
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            WhatsAppWebhookPayload payload = objectMapper.readValue(rawPayload, WhatsAppWebhookPayload.class);
            
            // Classify event type
            String eventType = webhookService.classifyEventType(payload);
            log.debug("Event type: {}", eventType);
            
            // Route to appropriate handler
            switch (eventType) {
                case "MESSAGE" -> inboundMessageService.processInboundMessage(payload);
                case "DELIVERY_RECEIPT" -> inboundMessageService.processDeliveryReceipt(payload);
                case "UNKNOWN" -> log.warn("Unknown webhook event type");
            }
            
            // Always return 200 OK to Meta (even if processing fails)
            // Failures should be retried by our retry service
            return ResponseEntity.ok().build();
            
        } catch (Exception e) {
            log.error("Unexpected error processing webhook event: {}", e.getMessage(), e);
            return ResponseEntity.ok().build();
        }
    }
}
