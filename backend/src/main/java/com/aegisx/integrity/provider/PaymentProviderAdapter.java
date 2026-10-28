package com.aegisx.integrity.provider;

import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.TransferState;

import java.time.Instant;
import java.util.List;

/**
 * The only place in the integrity engine that knows a specific provider's API shape.
 *
 * <p>Every method is read-only except {@link #createOrder} and {@link #createRefund}, which are
 * never called by automatic recovery. Endpoint shapes are taken from the provider's published
 * documentation (see the literature/competitor survey). No endpoint is invented.</p>
 */
public interface PaymentProviderAdapter {

    ProviderMode mode();

    /** Provider name as it appears in persisted rows, for example {@code RAZORPAY} or {@code SIMULATED}. */
    String providerName();

    boolean isSimulated();

    /** Read-only reachability probe used by the workspace configuration screen. */
    CapabilityStatus connectivity();

    CapabilityStatus transferCapability();

    ProviderOutcome<OrderSnapshot> createOrder(CreateOrderCommand command);

    ProviderOutcome<OrderSnapshot> fetchOrder(String providerOrderId);

    ProviderOutcome<List<PaymentSnapshot>> fetchOrderPayments(String providerOrderId);

    ProviderOutcome<PaymentSnapshot> fetchPayment(String providerPaymentId);

    ProviderOutcome<RefundSnapshot> fetchRefund(String providerRefundId);

    ProviderOutcome<List<RefundSnapshot>> fetchPaymentRefunds(String providerPaymentId);

    ProviderOutcome<TransferSnapshot> fetchTransfer(String providerTransferId);

    /**
     * Verifies a webhook signature over the <em>raw</em> request body.
     *
     * @return true when the signature is valid for this provider's configured webhook secret
     */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /** Amount in integer minor units plus ISO-4217 currency. Never floating point. */
    record CreateOrderCommand(long amountMinor, String currency, String internalReference, String description) { }

    record OrderSnapshot(
            String providerOrderId,
            OrderState state,
            long amountMinor,
            String currency,
            String receipt,
            Instant createdAt,
            Instant observedAt) { }

    record PaymentSnapshot(
            String providerPaymentId,
            String providerOrderId,
            PaymentState state,
            long amountMinor,
            String currency,
            String method,
            String failureReason,
            Instant createdAt,
            Instant observedAt) { }

    record RefundSnapshot(
            String providerRefundId,
            String providerPaymentId,
            RefundState state,
            long amountMinor,
            String currency,
            Instant createdAt,
            Instant observedAt) { }

    record TransferSnapshot(
            String providerTransferId,
            String providerPaymentId,
            TransferState state,
            long amountMinor,
            String currency,
            String linkedAccountId,
            Instant createdAt,
            Instant observedAt) { }
}
