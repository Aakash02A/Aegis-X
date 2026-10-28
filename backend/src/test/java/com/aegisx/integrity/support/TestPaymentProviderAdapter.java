package com.aegisx.integrity.support;

import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.provider.PaymentProviderAdapter;
import com.aegisx.integrity.provider.ProviderOutcome;
import com.aegisx.integrity.webhook.IntegrityWebhookSignatureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-scope deterministic provider fixture for offline integration tests.
 *
 * <p>It models the documented Razorpay resource lifecycle but is an ordinary in-process object. It
 * exists because a provider's real Test Mode cannot be asked to produce a fault on demand: the
 * specification forbids altering real provider transactions, so faults are injected here instead.</p>
 *
 * <p><strong>Every value this adapter returns is labelled simulated.</strong> The flag travels into
 * observations, findings, DTOs, the dashboard and the audit trail, so a simulated outcome can never
 * be mistaken for a provider-confirmed one.</p>
 *
 * <p>The control methods ({@code register*}, {@code setReachable}, {@code reset}, {@code
 * advanceClock}) let a test or benchmark establish an exact provider truth and then check whether
 * the engine discovers it.</p>
 */
@Slf4j
@Component
public class TestPaymentProviderAdapter implements PaymentProviderAdapter {

    private final IntegrityWebhookSignatureService signatureService;

    private final Map<String, OrderSnapshot> orders = new ConcurrentHashMap<>();
    private final Map<String, PaymentSnapshot> payments = new ConcurrentHashMap<>();
    private final Map<String, RefundSnapshot> refunds = new ConcurrentHashMap<>();
    private final Map<String, TransferSnapshot> transfers = new ConcurrentHashMap<>();
    private final Map<String, String> paymentToOrder = new ConcurrentHashMap<>();

    private final AtomicBoolean reachable = new AtomicBoolean(true);
    private final AtomicBoolean transfersEnabled = new AtomicBoolean(true);

    /**
     * Identifier counter shared by <em>every</em> adapter instance in the JVM.
     *
     * <p>It is deliberately static. Provider resource identifiers are a global namespace, but a Spring
     * application context is not: a second context (for example one created by
     * {@code @AutoConfigureMockMvc}) instantiates its own adapter while sharing the same database. With
     * a per-instance counter, both adapters start at the same seed and mint the same identifiers, so
     * one workspace's order can be handed another workspace's payment as an "orphan". That is a defect
     * in the fixture, not in the engine under test, and it made the suite order-dependent.</p>
     */
    private static final AtomicLong GLOBAL_SEQUENCE = new AtomicLong(0);

    /**
     * Per-process salt mixed into every generated identifier.
     *
     * <p>The sequence above is static, but it starts at zero on every JVM start while the rows it
     * produced in an earlier run are still in the database. Restarting the backend against a
     * persisted workspace therefore re-minted the same {@code order_SIM…} / {@code pay_SIM…}
     * identifiers, and the next order insert failed the {@code uq_pi_orders_provider_order}
     * constraint with an HTTP 500. The salt keeps identifiers unique across restarts while leaving
     * them deterministic and ordered within a single process, which is what the simulator promises.
     *</p>
     *
     * <p>It is 48 bits, not the 24 it started with. The sequence restarts at zero while earlier rows
     * survive, so the salt is the only thing separating two runs: at 24 bits a restart collided with
     * a stored identifier once in every ~16.7 million restarts, which is small but not a property
     * worth writing down as a guarantee. At 48 bits the same collision needs ~2.8e14 restarts. The
     * identifier stays far inside the {@code VARCHAR(100)} provider columns even with the widest
     * seed.</p>
     */
    private static final long PROCESS_SALT = new java.security.SecureRandom().nextLong() & 0xFFFFFFFFFFFFL;

    private static final String WEBHOOK_SECRET = "test-only-integrity-webhook-secret";
    private static final long ID_SEED = 20261010L;

    public TestPaymentProviderAdapter(IntegrityWebhookSignatureService signatureService) {
        this.signatureService = signatureService;
    }

    @Override
    public ProviderMode mode() {
        return ProviderMode.SIMULATED;
    }

    @Override
    public String providerName() {
        return "SIMULATED";
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public CapabilityStatus connectivity() {
        if (!reachable.get()) {
            return CapabilityStatus.UNAVAILABLE;
        }
        return CapabilityStatus.SIMULATED;
    }

    @Override
    public CapabilityStatus transferCapability() {
        if (!reachable.get()) {
            return CapabilityStatus.UNAVAILABLE;
        }
        return transfersEnabled.get() ? CapabilityStatus.SIMULATED : CapabilityStatus.NOT_ENABLED;
    }

    @Override
    public ProviderOutcome<OrderSnapshot> createOrder(CreateOrderCommand command) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        String id = nextId("order_SIM");
        Instant now = Instant.now();
        OrderSnapshot snapshot = new OrderSnapshot(
                id, OrderState.CREATED, command.amountMinor(), command.currency(),
                command.internalReference(), now, now);
        orders.put(id, snapshot);
        return ProviderOutcome.ok(snapshot, true);
    }

    @Override
    public ProviderOutcome<OrderSnapshot> fetchOrder(String providerOrderId) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        OrderSnapshot snapshot = orders.get(providerOrderId);
        return snapshot == null
                ? ProviderOutcome.notFound("simulated provider has no order " + providerOrderId, true)
                : ProviderOutcome.ok(snapshot, true);
    }

    @Override
    public ProviderOutcome<List<PaymentSnapshot>> fetchOrderPayments(String providerOrderId) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        if (!orders.containsKey(providerOrderId)) {
            return ProviderOutcome.notFound("simulated provider has no order " + providerOrderId, true);
        }
        List<PaymentSnapshot> matches = new ArrayList<>();
        for (PaymentSnapshot payment : payments.values()) {
            if (providerOrderId.equals(payment.providerOrderId())) {
                matches.add(payment);
            }
        }
        return ProviderOutcome.ok(List.copyOf(matches), true);
    }

    @Override
    public ProviderOutcome<PaymentSnapshot> fetchPayment(String providerPaymentId) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        PaymentSnapshot snapshot = payments.get(providerPaymentId);
        return snapshot == null
                ? ProviderOutcome.notFound("simulated provider has no payment " + providerPaymentId, true)
                : ProviderOutcome.ok(snapshot, true);
    }

    @Override
    public ProviderOutcome<RefundSnapshot> fetchRefund(String providerRefundId) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        RefundSnapshot snapshot = refunds.get(providerRefundId);
        return snapshot == null
                ? ProviderOutcome.notFound("simulated provider has no refund " + providerRefundId, true)
                : ProviderOutcome.ok(snapshot, true);
    }

    @Override
    public ProviderOutcome<List<RefundSnapshot>> fetchPaymentRefunds(String providerPaymentId) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        List<RefundSnapshot> matches = new ArrayList<>();
        for (RefundSnapshot refund : refunds.values()) {
            if (providerPaymentId.equals(refund.providerPaymentId())) {
                matches.add(refund);
            }
        }
        return ProviderOutcome.ok(List.copyOf(matches), true);
    }

    @Override
    public ProviderOutcome<TransferSnapshot> fetchTransfer(String providerTransferId) {
        if (!reachable.get()) {
            return ProviderOutcome.unavailable("simulated provider unreachable", true);
        }
        TransferSnapshot snapshot = transfers.get(providerTransferId);
        return snapshot == null
                ? ProviderOutcome.notFound("simulated provider has no transfer " + providerTransferId, true)
                : ProviderOutcome.ok(snapshot, true);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return signatureService.verify(rawBody, signature, WEBHOOK_SECRET);
    }

    // ---------------------------------------------------------------------
    // Control surface: used by fixtures, tests and the benchmark. Not called by production paths.
    // ---------------------------------------------------------------------

    /** Signature the simulated provider would put on this body, so fixtures can sign deliveries. */
    public String sign(String rawBody) {
        return signatureService.compute(rawBody, WEBHOOK_SECRET);
    }

    public void setReachable(boolean value) {
        reachable.set(value);
    }

    public void setTransfersEnabled(boolean value) {
        transfersEnabled.set(value);
    }

    /**
     * Returns the provider to an empty, reachable state.
     *
     * <p>The identifier counter is deliberately <strong>not</strong> rewound. Rewinding it let a newly
     * created resource reuse an identifier that a still-persisted database row already referenced, which
     * is how a workspace could end up reporting another test's payment as its own orphan. Identifiers are
     * cheap; collisions are not. Use {@link #deterministicId(String, int)} where a reproducible name is
     * genuinely required.</p>
     */
    public void reset() {
        orders.clear();
        payments.clear();
        refunds.clear();
        transfers.clear();
        paymentToOrder.clear();
        reachable.set(true);
        transfersEnabled.set(true);
    }

    /** Establishes a provider-confirmed order. Returns the provider order id. */
    public String registerOrder(long amountMinor, String currency, OrderState state) {
        String id = nextId("order_SIM");
        Instant now = Instant.now();
        orders.put(id, new OrderSnapshot(id, state, amountMinor, currency, id, now, now));
        return id;
    }

    /** Establishes a provider-confirmed payment against a known order. Returns the provider payment id. */
    public String registerPayment(String providerOrderId, long amountMinor, String currency, PaymentState state) {
        String id = nextId("pay_SIM");
        Instant now = Instant.now();
        payments.put(id, new PaymentSnapshot(id, providerOrderId, state, amountMinor, currency,
                "card", state == PaymentState.FAILED ? "simulated_failure" : null, now, now));
        if (providerOrderId != null) {
            paymentToOrder.put(id, providerOrderId);
        }
        settleOrderIfCovered(providerOrderId, amountMinor, state);
        return id;
    }

    /**
     * Models the documented provider behaviour that an order becomes {@code paid} once it is fully
     * paid. Without this the simulated provider would leave every order at {@code created} forever,
     * which is not a fault in the application under test but an inaccuracy in the fixture: a
     * correctly captured payment would be reported as an order-level status mismatch.
     *
     * <p>A payment that does not cover the order is deliberately not promoted, because a partially
     * paid order genuinely is not settled.</p>
     */
    private void settleOrderIfCovered(String providerOrderId, long amountMinor, PaymentState state) {
        if (providerOrderId == null || state == null || !state.isConfirmedSuccess() || amountMinor <= 0) {
            return;
        }
        OrderSnapshot existing = orders.get(providerOrderId);
        if (existing == null || existing.state() == OrderState.PAID || amountMinor < existing.amountMinor()) {
            return;
        }
        orders.put(providerOrderId, new OrderSnapshot(existing.providerOrderId(), OrderState.PAID,
                existing.amountMinor(), existing.currency(), existing.receipt(), existing.createdAt(), Instant.now()));
    }

    public String registerRefund(String providerPaymentId, long amountMinor, String currency, RefundState state) {
        String id = nextId("rfnd_SIM");
        Instant now = Instant.now();
        refunds.put(id, new RefundSnapshot(id, providerPaymentId, state, amountMinor, currency, now, now));
        return id;
    }

    public String registerTransfer(String providerPaymentId, long amountMinor, String currency, TransferState state) {
        String id = nextId("trf_SIM");
        Instant now = Instant.now();
        transfers.put(id, new TransferSnapshot(id, providerPaymentId, state, amountMinor, currency,
                "acc_SIMLINKED", now, now));
        return id;
    }

    /** Moves an existing simulated payment to a new state, modelling a provider-side change. */
    public boolean updatePaymentState(String providerPaymentId, PaymentState state) {
        PaymentSnapshot existing = payments.get(providerPaymentId);
        if (existing == null) {
            return false;
        }
        payments.put(providerPaymentId, new PaymentSnapshot(existing.providerPaymentId(), existing.providerOrderId(),
                state, existing.amountMinor(), existing.currency(), existing.method(), existing.failureReason(),
                existing.createdAt(), Instant.now()));
        settleOrderIfCovered(existing.providerOrderId(), existing.amountMinor(), state);
        return true;
    }

    public boolean updateOrderState(String providerOrderId, OrderState state) {
        OrderSnapshot existing = orders.get(providerOrderId);
        if (existing == null) {
            return false;
        }
        orders.put(providerOrderId, new OrderSnapshot(existing.providerOrderId(), state, existing.amountMinor(),
                existing.currency(), existing.receipt(), existing.createdAt(), Instant.now()));
        return true;
    }

    public Optional<OrderSnapshot> order(String providerOrderId) {
        return Optional.ofNullable(orders.get(providerOrderId));
    }

    public Optional<PaymentSnapshot> payment(String providerPaymentId) {
        return Optional.ofNullable(payments.get(providerPaymentId));
    }

    public int registeredOrderCount() {
        return orders.size();
    }

    public int registeredPaymentCount() {
        return payments.size();
    }

    private String nextId(String prefix) {
        long base = ID_SEED;
        long value = base + PROCESS_SALT + GLOBAL_SEQUENCE.incrementAndGet();
        return prefix + Long.toHexString(value).toUpperCase();
    }

    /** Static helper used by the benchmark to deterministically name provider resources. */
    public static String deterministicId(String prefix, int index) {
        return prefix + String.format("%06X", index);
    }
}
