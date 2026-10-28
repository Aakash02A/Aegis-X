package com.aegisx.integrity.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Receives provider webhook deliveries.
 *
 * <p>Unauthenticated by design — the provider cannot present a bearer token — so authenticity comes
 * exclusively from the signature, which is verified over the raw body before anything is parsed or
 * persisted. The endpoint is intentionally narrow: it can only ingest, never execute a payment
 * operation.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/integrity/webhooks")
@RequiredArgsConstructor
public class IntegrityWebhookController {

    private final IntegrityWebhookIngestionService ingestionService;

    @PostMapping("/razorpay/{workspaceId}")
    public ResponseEntity<Map<String, Object>> receiveRazorpayWebhook(
            @PathVariable UUID workspaceId,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {

        IntegrityWebhookIngestionService.IngestionResult result =
                ingestionService.ingest(workspaceId, rawBody, signature, eventId);
        // A duplicate is still a successful consumption: the provider requires 2xx.
        return ResponseEntity.ok(IntegrityWebhookIngestionService.toResponse(result));
    }
}
