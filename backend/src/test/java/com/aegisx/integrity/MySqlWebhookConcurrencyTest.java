package com.aegisx.integrity;

import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import com.aegisx.integrity.webhook.IntegrityWebhookIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent duplicate webhook delivery against a real MySQL 8.4 server.
 *
 * <p>This is the test the engine-independent ingestion fix exists for. Several deliveries of the same
 * provider event id arrive at once; the unique constraint on
 * {@code pi_webhook_events(provider, provider_event_id)} decides which one applies the business
 * effect, and every loser must be acknowledged as a duplicate rather than fail.</p>
 *
 * <p>Why it has to run here and not on H2: the whole difficulty is what happens <em>after</em> a
 * constraint violation, and the two engines disagree. MySQL rolls back only the failing statement, so
 * the old code appeared to work; PostgreSQL aborts the entire transaction, so it could never have
 * worked. H2 neither enforces the constraint the same way nor reproduces that difference, which is
 * why the reservation now runs in its own transaction. This test is what makes the claim measurable
 * instead of argued.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Concurrent webhook delivery on MySQL")
class MySqlWebhookConcurrencyTest extends MySqlIntegrityTestSupport {

    private static final long ORDER_AMOUNT = 60_000L;

    private record Outcome(int delivered, int duplicates, int applied, List<String> failures) { }

    /**
     * Fires {@code n} deliveries of the same event id at the same moment and reports what each one
     * did. Exceptions are collected rather than swallowed, because "no loser returned an error" is
     * one of the properties under test.
     */
    private Outcome deliverConcurrently(Workspace workspace, String body, String eventId, int n)
            throws InterruptedException {
        String signature = simulatedProvider.sign(body);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IntegrityWebhookIngestionService.IngestionResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                Callable<IntegrityWebhookIngestionService.IngestionResult> task = () -> {
                    start.await(30, TimeUnit.SECONDS);
                    return ingestionService.ingest(workspace.getId(), body, signature, eventId);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            int duplicates = 0;
            int applied = 0;
            List<String> failures = new ArrayList<>();
            for (Future<IntegrityWebhookIngestionService.IngestionResult> future : futures) {
                try {
                    IntegrityWebhookIngestionService.IngestionResult result = future.get(60, TimeUnit.SECONDS);
                    if (result.duplicate()) {
                        duplicates++;
                    } else if (result.applied()) {
                        applied++;
                    }
                } catch (Exception e) {
                    failures.add(e.getClass().getSimpleName() + ": " + rootMessage(e));
                }
            }
            return new Outcome(n, duplicates, applied, failures);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private void assertIdempotencyHeld(Workspace workspace, String providerEventId, Outcome outcome,
                                       int expectedDeliveries) {
        assertThat(outcome.failures())
                .as("every delivery must be acknowledged; a loser must never surface as an error")
                .isEmpty();
        assertThat(outcome.applied())
                .as("exactly one delivery may apply the business effect")
                .isEqualTo(1);
        assertThat(outcome.duplicates())
                .as("every other delivery must be reported as a duplicate")
                .isEqualTo(expectedDeliveries - 1);

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_webhook_events WHERE workspace_id = ? AND provider_event_id = ?",
                Integer.class, workspace.getId().toString(), providerEventId))
                .as("the event id must be booked exactly once")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_webhook_deliveries WHERE workspace_id = ? AND provider_event_id = ?",
                Integer.class, workspace.getId().toString(), providerEventId))
                .as("every delivery is logged, including the repeats")
                .isEqualTo(expectedDeliveries);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_webhook_deliveries WHERE workspace_id = ? AND provider_event_id = ? "
                        + "AND first_delivery = b'1'",
                Integer.class, workspace.getId().toString(), providerEventId))
                .as("exactly one delivery may be flagged as the first")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_webhook_deliveries WHERE workspace_id = ? AND provider_event_id = ? "
                        + "AND signature_verified = b'0'",
                Integer.class, workspace.getId().toString(), providerEventId))
                .as("only a verified delivery is ever logged")
                .isZero();
    }

    @Test
    @DisplayName("two simultaneous deliveries of one event id produce exactly one business effect")
    void twoSimultaneousDeliveries() throws Exception {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        String paymentId = registerProviderPayment(order, ORDER_AMOUNT, PaymentState.CAPTURED);
        String providerEventId = "evt_concurrent_2_" + paymentId;

        Outcome outcome = deliverConcurrently(workspace, paymentEventBody("payment.captured", paymentId,
                order.getProviderOrderId(), ORDER_AMOUNT, "INR", "captured"), providerEventId, 2);

        assertIdempotencyHeld(workspace, providerEventId, outcome, 2);

        assertThat(paymentRepository.countByWorkspaceId(workspace.getId()))
                .as("the duplicate must not create a second payment record")
                .isEqualTo(1);
        assertThat(transitionRepository.findAllByWorkspaceIdOrderByOccurredAtDesc(workspace.getId()).stream()
                .filter(transition -> "CAPTURED".equals(transition.getToState())))
                .as("the capture transition must be applied exactly once")
                .hasSize(1);
    }

    @Test
    @DisplayName("eight simultaneous deliveries of one event id still produce exactly one business effect")
    void eightSimultaneousDeliveries() throws Exception {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        String paymentId = registerProviderPayment(order, ORDER_AMOUNT, PaymentState.CAPTURED);
        String providerEventId = "evt_concurrent_8_" + paymentId;

        Outcome outcome = deliverConcurrently(workspace, paymentEventBody("payment.captured", paymentId,
                order.getProviderOrderId(), ORDER_AMOUNT, "INR", "captured"), providerEventId, 8);

        assertIdempotencyHeld(workspace, providerEventId, outcome, 8);

        assertThat(paymentRepository.countByWorkspaceId(workspace.getId()))
                .as("eight concurrent deliveries must still create one payment record")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("sequential repeats stay idempotent as well")
    void repeatedSequentialDeliveries() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        String paymentId = registerProviderPayment(order, ORDER_AMOUNT, PaymentState.CAPTURED);
        String providerEventId = "evt_repeat_" + paymentId;
        String body = paymentEventBody("payment.captured", paymentId, order.getProviderOrderId(),
                ORDER_AMOUNT, "INR", "captured");

        assertThat(deliver(workspace, body, providerEventId).applied()).isTrue();
        for (int i = 0; i < 3; i++) {
            assertThat(deliver(workspace, body, providerEventId).duplicate())
                    .as("a repeated delivery must be acknowledged as a duplicate")
                    .isTrue();
        }

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_webhook_events WHERE workspace_id = ?",
                Integer.class, workspace.getId().toString()))
                .as("four deliveries of one event id produce one booking")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_webhook_deliveries WHERE workspace_id = ?",
                Integer.class, workspace.getId().toString()))
                .as("all four deliveries are logged")
                .isEqualTo(4);
    }
}
