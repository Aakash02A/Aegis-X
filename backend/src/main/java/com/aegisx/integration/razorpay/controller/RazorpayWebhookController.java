package com.aegisx.integration.razorpay.controller;

import com.aegisx.integration.razorpay.service.RazorpaySignatureService;
import com.aegisx.integration.razorpay.service.RazorpayWebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private final RazorpaySignatureService razorpaySignatureService;
    private final RazorpayWebhookDispatcher razorpayWebhookDispatcher;

    @PostMapping
    public ResponseEntity<Map<String, String>> receiveWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventIdHeader) {

        String correlationId = "WHK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        log.info("[{}] Received Razorpay webhook request (payload size: {} bytes, eventId: '{}')",
                correlationId, rawPayload != null ? rawPayload.length() : 0, eventIdHeader);

        // 1. Signature-First Verification
        razorpaySignatureService.verifySignature(rawPayload, signature);

        // 2. Dispatch to Idempotent Processing Engine
        // Future: Move dispatch to an async worker / event queue if webhook processing becomes expensive
        // to guarantee < 5s response SLA per Razorpay delivery specifications.
        razorpayWebhookDispatcher.dispatch(rawPayload, eventIdHeader, correlationId);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

