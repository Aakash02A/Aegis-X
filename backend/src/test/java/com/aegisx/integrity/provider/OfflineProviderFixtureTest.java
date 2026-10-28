package com.aegisx.integrity.provider;

import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.support.TestPaymentProviderAdapter;
import com.aegisx.integrity.webhook.IntegrityWebhookSignatureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for offline provider fixture behaviour that used to make the reconciliation
 * suite order-dependent.
 *
 * <p>A Spring application context is not a JVM: the suite builds several contexts, and each one gets
 * its own adapter while sharing one database. When identifiers were minted from a per-instance
 * counter, every adapter started at the same seed and handed out the same {@code order_SIM…} /
 * {@code pay_SIM…} identifiers. A workspace could then resolve its provider order to a different
 * resource that another context had registered, and reconciliation reported a conflict that did not
 * exist. These tests assert the properties that prevent that, independently of Spring.</p>
 */
@DisplayName("Offline provider fixture identifiers and settlement")
class OfflineProviderFixtureTest {

    private TestPaymentProviderAdapter newAdapter() {
        return new TestPaymentProviderAdapter(new IntegrityWebhookSignatureService());
    }

    private static PaymentProviderAdapter.CreateOrderCommand orderCommand(long amountMinor) {
        return new PaymentProviderAdapter.CreateOrderCommand(amountMinor, "INR", "ORD-REG-01", "regression order");
    }

    @Test
    @DisplayName("two adapter instances never mint the same provider identifier")
    void identifiersAreUniqueAcrossAdapterInstances() {
        TestPaymentProviderAdapter first = newAdapter();
        TestPaymentProviderAdapter second = newAdapter();

        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            assertThat(issued.add(first.createOrder(orderCommand(1_000)).value().providerOrderId()))
                    .as("the first adapter must not reissue an identifier")
                    .isTrue();
            assertThat(issued.add(second.createOrder(orderCommand(1_000)).value().providerOrderId()))
                    .as("a second context's adapter must not collide with the first")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("concurrent callers on the shared counter never mint the same identifier")
    void concurrentMintingIsUnique() throws Exception {
        // The counter is static and shared by every adapter in the JVM, so it is exercised from
        // several threads here: a plain long field would pass the sequential tests above and still
        // hand two callers the same provider id under load.
        TestPaymentProviderAdapter adapter = newAdapter();
        int threads = 8;
        int perThread = 200;
        Set<String> issued = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        if (!issued.add(adapter.createOrder(orderCommand(1_000)).value().providerOrderId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                });
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(duplicates.get()).as("two threads must never receive the same provider identifier").isZero();
        assertThat(issued).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("reset clears resources but never rewinds the identifier sequence")
    void resetDoesNotReissueIdentifiers() {
        TestPaymentProviderAdapter adapter = newAdapter();
        String beforeReset = adapter.createOrder(orderCommand(1_000)).value().providerOrderId();

        adapter.reset();
        String afterReset = adapter.createOrder(orderCommand(1_000)).value().providerOrderId();

        assertThat(afterReset)
                .as("a reissued identifier could match a provider id already persisted in another workspace")
                .isNotEqualTo(beforeReset);
        assertThat(adapter.registeredOrderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a captured payment that covers the order settles the provider order")
    void fullyCoveringCaptureSettlesTheOrder() {
        TestPaymentProviderAdapter adapter = newAdapter();
        String orderId = adapter.createOrder(orderCommand(80_000)).value().providerOrderId();

        adapter.registerPayment(orderId, 80_000, "INR", PaymentState.CAPTURED);

        assertThat(adapter.order(orderId))
                .get()
                .extracting(PaymentProviderAdapter.OrderSnapshot::state)
                .as("the provider documents that a fully paid order becomes paid; leaving it CREATED "
                        + "would make a correct capture look like an order-level status mismatch")
                .isEqualTo(OrderState.PAID);
    }

    @Test
    @DisplayName("a partial or failed payment does not settle the order")
    void partialOrFailedPaymentDoesNotSettleTheOrder() {
        TestPaymentProviderAdapter adapter = newAdapter();
        String orderId = adapter.createOrder(orderCommand(80_000)).value().providerOrderId();

        adapter.registerPayment(orderId, 75_000, "INR", PaymentState.CAPTURED);
        assertThat(adapter.order(orderId)).get()
                .extracting(PaymentProviderAdapter.OrderSnapshot::state)
                .isEqualTo(OrderState.CREATED);

        adapter.registerPayment(orderId, 5_000, "INR", PaymentState.FAILED);
        assertThat(adapter.order(orderId)).get()
                .extracting(PaymentProviderAdapter.OrderSnapshot::state)
                .as("a failed payment is not money received")
                .isEqualTo(OrderState.CREATED);
    }
}
