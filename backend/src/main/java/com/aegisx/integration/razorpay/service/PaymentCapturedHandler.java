package com.aegisx.integration.razorpay.service;

import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCapturedHandler implements RazorpayEventHandler {

    public static final String EVENT_TYPE = "payment.captured";

    private final TransactionRepository transactionRepository;
    private final TransactionStateMachine transactionStateMachine;

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId) {
        String paymentId = payload.getPaymentId();
        event.setPaymentId(paymentId);

        // Phase 06 MVP Multi-Tiered Transaction Lookup:
        // 1. Direct reference number matching
        // 2. Razorpay order_id (preferred long-term mapping)
        // 3. notes.transaction_reference (fallback for MVP)
        // TODO Phase 06 MVP: Using notes.transaction_reference. Replace with persisted Razorpay Order mapping in future.
        String referenceHint = payload.getTransactionReferenceFromNotes();
        if (referenceHint == null) {
            referenceHint = payload.getOrderId();
        }

        log.info("[{}] Processing '{}' for paymentId: '{}', referenceHint: '{}'",
                correlationId, EVENT_TYPE, paymentId, referenceHint);

        if (referenceHint == null || referenceHint.isBlank()) {
            log.warn("[{}] No reference number or orderId found in payment.captured payload for paymentId: '{}'",
                    correlationId, paymentId);
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }

        Optional<Transaction> transactionOpt = transactionRepository.findByReferenceNumber(referenceHint);
        if (transactionOpt.isEmpty()) {
            log.warn("[{}] Unknown transaction reference '{}' received for paymentId '{}'",
                    correlationId, referenceHint, paymentId);
            event.setTransactionReference(referenceHint);
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }

        Transaction transaction = transactionOpt.get();
        event.setTransactionReference(transaction.getReferenceNumber());

        // Out-of-Order Webhook Resilience:
        // Razorpay webhook delivery order is not strictly guaranteed (e.g. payment.captured arriving
        // after other processing or redundant retries). If the transaction is not in CREATED status,
        // we safely ignore redundant transitions rather than throwing an exception.
        if (transaction.getStatus() == TransactionStatus.CREATED) {
            log.info("[{}] Advancing transaction {} ({}) status: CREATED -> PAYMENT_RECEIVED -> EVIDENCE_PENDING",
                    correlationId, transaction.getId(), transaction.getReferenceNumber());

            // 1. CREATED -> PAYMENT_RECEIVED (Triggered by Razorpay webhook)
            transactionStateMachine.transition(
                    transaction,
                    TransactionStatus.PAYMENT_RECEIVED,
                    TransitionActor.RAZORPAY,
                    "Razorpay payment captured: " + paymentId
            );

            // 2. PAYMENT_RECEIVED -> EVIDENCE_PENDING (Awaiting invoice upload)
            transactionStateMachine.transition(
                    transaction,
                    TransactionStatus.EVIDENCE_PENDING,
                    TransitionActor.SYSTEM,
                    "Payment confirmed; awaiting merchant compliance evidence"
            );

            transactionRepository.save(transaction);
            event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
        } else {
            log.info("[{}] Out-of-order / replay event: Transaction {} ({}) is already at status '{}'; skipping transition",
                    correlationId, transaction.getId(), transaction.getReferenceNumber(), transaction.getStatus());
            event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
        }
    }
}

