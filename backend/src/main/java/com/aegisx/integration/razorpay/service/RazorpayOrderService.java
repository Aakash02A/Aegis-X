package com.aegisx.integration.razorpay.service;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.service.AuditService;
import com.aegisx.integration.razorpay.config.RazorpayProperties;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.integration.razorpay.dto.CreatePaymentOrderResponse;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RazorpayOrderService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final RazorpayOrderClient razorpayOrderClient;
    private final RazorpayProperties razorpayProperties;
    private final AuditService auditService;

    public boolean isCheckoutConfigured() {
        return razorpayProperties.isConfigured();
    }

    @Transactional
    public CreatePaymentOrderResponse createOrder(UUID transactionId, String merchantEmail) {
        User merchant = userRepository.findByEmail(merchantEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        Transaction transaction = transactionRepository.findByIdAndMerchantIdForUpdate(transactionId, merchant.getId())
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));

        if (transaction.getStatus() != TransactionStatus.CREATED) {
            throw new IllegalStateException("A payment order can only be created for a transaction in CREATED status");
        }

        long amountMinor = toMinorUnits(transaction.getAmount(), transaction.getCurrency());
        if (transaction.getRazorpayOrderId() == null) {
            JsonNode existingOrders = razorpayOrderClient.findOrdersByReceipt(transaction.getReferenceNumber());
            JsonNode items = existingOrders == null ? null : existingOrders.get("items");
            if (items == null || !items.isArray()) {
                throw new IllegalStateException("Razorpay order lookup returned an incomplete response");
            }
            if (items.size() >= 100) {
                throw new IllegalStateException("Multiple Razorpay orders require operator reconciliation");
            }

            JsonNode matchingOrder = findMatchingOrder(items, transaction, amountMinor);
            if (matchingOrder == null) {
                matchingOrder = razorpayOrderClient.createOrder(amountMinor, transaction.getCurrency(),
                        transaction.getReferenceNumber(), Map.of(
                                "transaction_id", transaction.getId().toString(),
                                "transaction_reference", transaction.getReferenceNumber()));
            }
            validateOrderResponse(matchingOrder, transaction, amountMinor);
            transaction.setRazorpayOrderId(matchingOrder.path("id").asText());
            transactionRepository.saveAndFlush(transaction);
            auditService.logEvent(AuditEventType.PAYMENT_ORDER_CREATED, AuditModule.RAZORPAY,
                    "Razorpay order bound to transaction", transaction.getId());
        }

        return new CreatePaymentOrderResponse(
                transaction.getId(),
                transaction.getRazorpayOrderId(),
                razorpayProperties.getKeyId(),
                amountMinor,
                transaction.getCurrency(),
                transaction.getAmount());
    }

    private JsonNode findMatchingOrder(JsonNode items, Transaction transaction, long amountMinor) {
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("Razorpay order lookup returned an incomplete response");
        }
        JsonNode match = null;
        for (JsonNode candidate : items) {
            if (!transaction.getReferenceNumber().equals(candidate.path("receipt").asText())) {
                continue;
            }
            validateOrderResponse(candidate, transaction, amountMinor);
            if (match != null) {
                throw new IllegalStateException("Multiple Razorpay orders require operator reconciliation");
            }
            match = candidate;
        }
        return match;
    }

    private void validateOrderResponse(JsonNode order, Transaction transaction, long amountMinor) {
        if (order == null || order.path("id").asText().isBlank()
                || !transaction.getReferenceNumber().equals(order.path("receipt").asText())
                || order.path("amount").asLong(-1) != amountMinor
                || !transaction.getCurrency().equalsIgnoreCase(order.path("currency").asText())) {
            throw new IllegalStateException("Razorpay order does not match the transaction; operator review is required");
        }
    }

    private long toMinorUnits(BigDecimal amount, String currencyCode) {
        try {
            int fractionDigits = Currency.getInstance(currencyCode.toUpperCase(Locale.ROOT))
                    .getDefaultFractionDigits();
            if (fractionDigits < 0 || fractionDigits > 2) {
                throw new IllegalArgumentException("Transaction currency precision is not supported");
            }
            return amount.movePointRight(fractionDigits).longValueExact();
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Transaction amount cannot be represented in the currency's minor units", e);
        }
    }
}
