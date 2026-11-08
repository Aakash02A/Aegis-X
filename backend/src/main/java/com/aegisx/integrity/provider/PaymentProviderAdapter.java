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
 * <p>Order creation is the only mutating provider operation in this interface; all remaining
 * methods read provider state or verify webhook authenticity. Endpoint shapes are taken from the
 * provider's published documentation. No capture, refund, or payout operation is exposed here.</p>
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

    /**
     * Resource snapshots distinguish the provider's creation time from when Aegis-X received the
     * snapshot. {@code observedAt} is the response time, not the provider resource's last-update time.
     */
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
