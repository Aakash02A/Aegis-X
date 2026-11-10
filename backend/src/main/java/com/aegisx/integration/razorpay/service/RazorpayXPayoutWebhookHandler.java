package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RazorpayXPayoutWebhookHandler implements RazorpayEventHandler {

    private final SettlementService settlementService;

    @Override
    public boolean supports(String eventType) {
        return eventType != null && eventType.startsWith("payout.");
    }

    @Override
    public void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId) {
        RazorpayWebhookPayload.PayoutEntity payout = payload.getPayout();
        if (payout == null || payout.getId() == null || payout.getReferenceId() == null) {
            log.warn("[{}] RazorpayX payout webhook is missing payout identity fields", correlationId);
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }

        event.setPaymentId(payout.getId());
        try {
            String reason = payout.getStatusDetails() == null
                    ? null : payout.getStatusDetails().getDescription();
            if (reason != null && reason.length() > 500) {
                reason = reason.substring(0, 500);
            }
            String transactionReference = settlementService.applyRazorpayXPayoutWebhook(
                    payout.getId(),
                    payout.getReferenceId(),
                    payout.getAmount(),
                    payout.getCurrency(),
                    payout.getStatus(),
                    reason,
                    payout.getUtr());
            event.setTransactionReference(transactionReference);
            event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[{}] RazorpayX payout webhook rejected: {}", correlationId, e.getMessage());
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
        }
    }
}
