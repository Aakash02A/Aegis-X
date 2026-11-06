package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedHandler implements RazorpayEventHandler {

    public static final String EVENT_TYPE = "payment.failed";

    private final TransactionRepository transactionRepository;

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId) {
        String paymentId = payload.getPaymentId();
        event.setPaymentId(paymentId);
        String providerOrderId = payload.getOrderId();
        if (providerOrderId == null || providerOrderId.isBlank()) {
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }
        Optional<Transaction> transaction = transactionRepository.findByRazorpayOrderId(providerOrderId);
        if (transaction.isEmpty()) {
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }
        String transactionReference = transaction.get().getReferenceNumber();
        event.setTransactionReference(transactionReference);
        event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);

        String errorCode = null;
        String errorDescription = null;
        if (payload.getPayload() != null && payload.getPayload().getPayment() != null
                && payload.getPayload().getPayment().getEntity() != null) {
            errorCode = payload.getPayload().getPayment().getEntity().getErrorCode();
            errorDescription = payload.getPayload().getPayment().getEntity().getErrorDescription();
        }

        log.warn("[{}] Recorded '{}' for paymentId: '{}', reference: '{}', errorCode: '{}', reason: '{}'",
                correlationId, EVENT_TYPE, paymentId, transactionReference, errorCode, errorDescription);
    }
}
