package com.aegisx.integration.razorpay.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentOrderResponse(
        UUID transactionId,
        String orderId,
        String keyId,
        long amount,
        String currency,
        BigDecimal displayAmount
) {
}
