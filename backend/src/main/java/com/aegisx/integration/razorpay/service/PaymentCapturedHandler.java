package com.aegisx.integration.razorpay.service;

import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.service.AuditService;
import com.aegisx.document.service.DocumentService;
import com.aegisx.integration.razorpay.dto.RazorpayWebhookPayload;
import com.aegisx.integration.razorpay.entity.WebhookEvent;
import com.aegisx.integration.razorpay.entity.WebhookProcessingStatus;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionStateMachine;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCapturedHandler implements RazorpayEventHandler {

    public static final String EVENT_TYPE = "payment.captured";

    private final TransactionRepository transactionRepository;
    private final TransactionStateMachine transactionStateMachine;
    private final AuditService auditService;
    private final DocumentService documentService;

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(RazorpayWebhookPayload payload, WebhookEvent event, String correlationId) {
        String paymentId = payload.getPaymentId();
        event.setPaymentId(paymentId);

        String providerOrderId = payload.getOrderId();
        log.info("[{}] Processing '{}' for paymentId: '{}', orderId: '{}'",
                correlationId, EVENT_TYPE, paymentId, providerOrderId);

        if (providerOrderId == null || providerOrderId.isBlank()) {
            log.warn("[{}] No Razorpay order ID found in payment.captured payload for paymentId: '{}'",
                    correlationId, paymentId);
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }

        Optional<Transaction> transactionOpt = transactionRepository.findByRazorpayOrderId(providerOrderId);
        if (transactionOpt.isEmpty()) {
            log.warn("[{}] Unknown Razorpay order ID '{}' received for paymentId '{}'",
                    correlationId, providerOrderId, paymentId);
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }

        Transaction transaction = transactionOpt.get();
        event.setTransactionReference(transaction.getReferenceNumber());

        if (!matchesExpectedAmountAndCurrency(transaction, payload)) {
            log.warn("[{}] Captured payment {} does not match expected amount/currency for transaction {}",
                    correlationId, paymentId, transaction.getReferenceNumber());
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
            return;
        }

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
                    TransitionActor.RAZORPAY_GATEWAY,
                    "Razorpay payment captured: " + paymentId
            );

            // Record webhook event audit
            ObjectNode webhookData = JsonNodeFactory.instance.objectNode();
            webhookData.put("paymentId", paymentId);
            webhookData.put("amount", payload.getAmount());
            webhookData.put("currency", payload.getCurrency());

            auditService.recordWebhookEvent(
                transaction,
                AuditEventType.WEBHOOK_RECEIVED,
                AuditModule.RAZORPAY,
                correlationId,
                paymentId,
                "true",
                webhookData
            );

            // 2. PAYMENT_RECEIVED -> EVIDENCE_PENDING (Awaiting invoice upload)
            transactionStateMachine.transition(
                    transaction,
                    TransactionStatus.EVIDENCE_PENDING,
                    TransitionActor.SYSTEM,
                    "Payment confirmed; awaiting merchant compliance evidence"
            );

            transactionRepository.save(transaction);
            documentService.initializeChecklist(transaction.getId());

            // Record payment received event audit
            ObjectNode paymentData = JsonNodeFactory.instance.objectNode();
            paymentData.put("paymentId", paymentId);
            paymentData.put("status", "captured");

            auditService.recordStateTransition(
                transaction,
                AuditEventType.PAYMENT_RECEIVED,
                AuditModule.RAZORPAY,
                null,
                ActorType.WEBHOOK,
                correlationId,
                TransactionStatus.CREATED.name(),
                TransactionStatus.PAYMENT_RECEIVED.name(),
                paymentData
            );

            event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
        } else if (transaction.getStatus() == TransactionStatus.PAYMENT_FAILED) {
            log.error("[{}] Captured payment {} conflicts with terminal PAYMENT_FAILED transaction {}",
                    correlationId, paymentId, transaction.getReferenceNumber());
            event.setProcessingStatus(WebhookProcessingStatus.FAILED);
        } else {
            log.info("[{}] Out-of-order / replay event: Transaction {} ({}) is already at status '{}'; skipping transition",
                    correlationId, transaction.getId(), transaction.getReferenceNumber(), transaction.getStatus());
            event.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
        }
    }

    private boolean matchesExpectedAmountAndCurrency(Transaction transaction, RazorpayWebhookPayload payload) {
        BigDecimal providerAmountMinor = payload.getAmount();
        String providerCurrency = payload.getCurrency();
        String transactionCurrency = transaction.getCurrency();
        if (providerAmountMinor == null || providerCurrency == null || transactionCurrency == null
                || !transactionCurrency.equalsIgnoreCase(providerCurrency)) {
            return false;
        }

        try {
            int fractionDigits = Currency.getInstance(transactionCurrency.toUpperCase(Locale.ROOT))
                    .getDefaultFractionDigits();
            return fractionDigits >= 0
                    && transaction.getAmount() != null
                    && transaction.getAmount().compareTo(providerAmountMinor.movePointLeft(fractionDigits)) == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
