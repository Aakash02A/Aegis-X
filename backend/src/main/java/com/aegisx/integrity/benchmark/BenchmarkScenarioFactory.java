package com.aegisx.integrity.benchmark;

import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.OrderState;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.domain.TransferState;
import com.aegisx.integrity.evidence.EvidenceCheckService;
import com.aegisx.integrity.evidence.EvidenceFieldExtractor;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Generates the benchmark's scenarios deterministically.
 *
 * <p>Given the same seed this factory always produces byte-identical scenarios. Scenario parameters
 * (amounts, ages, thresholds) vary with a seeded {@link Random} so that results are not an artefact
 * of a single magic number, while the <em>structure</em> of each fault is fixed and published.</p>
 *
 * <p>The oracle sets are declared here, in code, before any method runs. They are not derived from
 * any method's output.</p>
 */
@Component
public class BenchmarkScenarioFactory {

    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-4000-8000-00000000b0b1");
    private static final Duration STALENESS = Duration.ofMinutes(15);

    /** Repetitions per fault kind. 18 kinds x 12 = 216 scenarios by default. */
    public List<BenchmarkScenario> generate(long seed, int repetitionsPerKind) {
        Random random = new Random(seed);
        List<BenchmarkScenario> scenarios = new ArrayList<>();
        for (FaultKind kind : FaultKind.values()) {
            for (int repetition = 0; repetition < repetitionsPerKind; repetition++) {
                boolean variantB = kind.hasTwoVariants() && repetition % 2 == 1;
                scenarios.add(build(kind, variantB, repetition, random));
            }
        }
        return List.copyOf(scenarios);
    }

    private BenchmarkScenario build(FaultKind kind, boolean variantB, int repetition, Random random) {
        long amount = 10_000L + random.nextInt(40) * 1_000L;
        Instant now = Instant.parse("2026-10-10T12:00:00Z");
        UUID orderId = UUID.nameUUIDFromBytes((kind + ":order:" + repetition).getBytes());
        UUID paymentId = UUID.nameUUIDFromBytes((kind + ":payment:" + repetition).getBytes());
        UUID refundId = UUID.nameUUIDFromBytes((kind + ":refund:" + repetition).getBytes());
        UUID transferId = UUID.nameUUIDFromBytes((kind + ":transfer:" + repetition).getBytes());
        String providerOrderId = "order_" + kind.name().substring(0, 3) + repetition;
        String providerPaymentId = "pay_" + kind.name().substring(0, 3) + repetition;

        Instant aged = now.minus(Duration.ofHours(2));
        // Route is treated as enabled, so transfer scenarios exercise the transfer rules rather
        // than short-circuiting on the "Route not enabled for this account" capability check. The
        // capability-gap branch is covered by a focused test instead.
        boolean transfersEnabled = true;

        List<EvidencePack.OrderView> orders = new ArrayList<>();
        List<EvidencePack.PaymentView> payments = new ArrayList<>();
        List<EvidencePack.RefundView> refunds = new ArrayList<>();
        List<EvidencePack.TransferView> transfers = new ArrayList<>();
        List<EvidencePack.ObservationView> observations = new ArrayList<>();
        List<EvidencePack.TransitionView> transitions = new ArrayList<>();
        List<EvidencePack.WebhookObservation> webhooks = new ArrayList<>();

        Set<FindingType> required = Set.of();
        Set<FindingType> acceptable = Set.of();
        Set<FindingType> critical = Set.of();
        boolean providerReachable = true;
        EvidenceCheckService.Input evidenceInput = null;
        String description;

        switch (kind) {
            case NORMAL_COMPLETION -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(600), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(300)));
                observations.add(observation(orderId, ResourceType.ORDER, providerOrderId, EvidenceSource.PROVIDER_API_CONFIRMED, OrderState.PAID.name(), amount, now.minusSeconds(300)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(300)));
                webhooks.add(webhook("evt_captured_" + repetition, "payment.captured", now.minusSeconds(280), false));
                description = "Legitimate path: everything agrees; any finding here is a false positive";
            }
            case DUPLICATE_WEBHOOK -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(600), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(300)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(300)));
                webhooks.add(webhook("evt_dup_" + repetition, "payment.captured", now.minusSeconds(280), false));
                webhooks.add(webhook("evt_dup_" + repetition, "payment.captured", now.minusSeconds(60), true));
                required = Set.of(FindingType.DUPLICATE_EVENT_DELIVERY);
                description = "The same provider event id is delivered twice; at-least-once semantics";
            }
            case OUT_OF_ORDER_WEBHOOK -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(900), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(300)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(300)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_WEBHOOK, PaymentState.FAILED.name(), amount, now.minusSeconds(60)));
                transitions.add(transition(ResourceType.PAYMENT, paymentId, PaymentState.CAPTURED.name(), PaymentState.FAILED.name(), true, now.minusSeconds(60)));
                webhooks.add(webhook("evt_late_failed_" + repetition, "payment.failed", now.minusSeconds(50), false));
                required = Set.of(FindingType.CONFLICTING_OBSERVATIONS);
                acceptable = Set.of(FindingType.STATUS_MISMATCH, FindingType.STALE_PAYMENT);
                description = "payment.failed arrives after capture was confirmed; the conflict is recorded, not applied";
            }
            case DELAYED_WEBHOOK -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.ATTEMPTED, OrderState.ATTEMPTED, now.minusSeconds(900), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.AUTHORIZED, PaymentState.CAPTURED, now.minusSeconds(120)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(120)));
                required = Set.of(FindingType.STATUS_MISMATCH);
                acceptable = Set.of(FindingType.STALE_PAYMENT);
                critical = Set.of(FindingType.STATUS_MISMATCH);
                description = "The capture webhook is delayed; the provider API already reports captured";
            }
            case TIMEOUT_UNKNOWN_OUTCOME -> {
                if (variantB) {
                    orders.add(order(orderId, providerOrderId, amount, OrderState.ATTEMPTED, OrderState.ATTEMPTED, now.minusSeconds(900), null));
                    payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.TIMEOUT_UNKNOWN, PaymentState.AUTHORIZED, aged));
                    observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.AUTHORIZED.name(), amount, aged));
                    required = Set.of(FindingType.STATUS_MISMATCH);
                    acceptable = Set.of(FindingType.STALE_PAYMENT);
                    critical = Set.of(FindingType.STATUS_MISMATCH);
                    description = "Our call timed out; the provider reports AUTHORIZED, which is not a failure";
                } else {
                    providerReachable = false;
                    orders.add(order(orderId, providerOrderId, amount, OrderState.ATTEMPTED, OrderState.ATTEMPTED, aged, null));
                    payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.TIMEOUT_UNKNOWN, PaymentState.CREATED, aged));
                    required = Set.of(FindingType.STALE_PAYMENT);
                    critical = Set.of(FindingType.STALE_PAYMENT);
                    description = "Our call timed out and the provider cannot be reached: the outcome is unknown, not failed";
                }
            }
            case STATUS_MISMATCH -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(900), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.FAILED, now.minusSeconds(200)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.FAILED.name(), amount, now.minusSeconds(200)));
                required = Set.of(FindingType.STATUS_MISMATCH);
                critical = Set.of(FindingType.STATUS_MISMATCH);
                description = "Internal CAPTURED versus provider-confirmed FAILED";
            }
            case AMOUNT_MISMATCH -> {
                long providerAmount = amount - 5_000L;
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(900), providerAmount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(200)));
                observations.add(observation(orderId, ResourceType.ORDER, providerOrderId, EvidenceSource.PROVIDER_API_CONFIRMED, OrderState.PAID.name(), providerAmount, now.minusSeconds(200)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), providerAmount, now.minusSeconds(200)));
                required = Set.of(FindingType.AMOUNT_MISMATCH);
                critical = Set.of(FindingType.AMOUNT_MISMATCH);
                description = "Provider paid a different amount than the order expected";
            }
            case MISSING_PROVIDER_OBSERVATION -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.CREATED, OrderState.CREATED, aged, null));
                observations.add(observation(orderId, ResourceType.ORDER, providerOrderId, EvidenceSource.PROVIDER_API_CONFIRMED, OrderState.CREATED.name(), amount, aged));
                required = Set.of(FindingType.INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT);
                critical = Set.of(FindingType.INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT);
                description = "The order is still open well past the threshold and no payment exists";
            }
            case REFUND_DISCREPANCY -> {
                if (variantB) {
                    orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(900), amount));
                    payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(300)));
                    observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(300)));
                    observations.add(new EvidencePack.ObservationView(null, ResourceType.REFUND,
                            "rfnd_orphan_" + repetition, EvidenceSource.PROVIDER_API_CONFIRMED, 4,
                            RefundState.PROCESSED.name(), amount, "INR", now.minusSeconds(120), false));
                    required = Set.of(FindingType.REFUND_DISCREPANCY);
                    critical = Set.of(FindingType.REFUND_DISCREPANCY);
                    description = "The provider reports a refund Aegis-X has no record of";
                } else {
                    orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(900), amount));
                    payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.REFUNDED, now.minusSeconds(200)));
                    refunds.add(new EvidencePack.RefundView(refundId, paymentId, "rfnd_" + repetition,
                            amount / 2, "INR", RefundState.PENDING, now.minusSeconds(200), false));
                    required = Set.of(FindingType.REFUND_DISCREPANCY);
                    critical = Set.of(FindingType.REFUND_DISCREPANCY);
                    description = "The payment is marked refunded but refund records cover only half the amount";
                }
            }
            case TRANSFER_DISCREPANCY -> {
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(900), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(300)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(300)));
                if (variantB) {
                    // Variant B: a transfer that never settled. Its age must genuinely exceed the
                    // staleness threshold, otherwise the scenario declares a fault it never injects.
                    transfers.add(new EvidencePack.TransferView(transferId, paymentId, orderId, "trf_" + repetition,
                            amount, "INR", TransferState.CREATED, aged, false));
                    description = "A transfer sits unprocessed well past the threshold for a captured payment";
                } else {
                    // Variant A: more money left the captured payment than it ever held.
                    transfers.add(new EvidencePack.TransferView(transferId, paymentId, orderId, "trf_" + repetition,
                            amount * 2, "INR", TransferState.PROCESSED, now.minusSeconds(300), false));
                    description = "Transfers out of the captured payment exceed the amount that was captured";
                }
                required = Set.of(FindingType.TRANSFER_DISCREPANCY);
                critical = Set.of(FindingType.TRANSFER_DISCREPANCY);
            }
            case EVIDENCE_CLEAN, EVIDENCE_MISSING_INVOICE, EVIDENCE_UNREADABLE_DOCUMENT,
                 EVIDENCE_AMOUNT_MISMATCH, EVIDENCE_CURRENCY_MISMATCH, EVIDENCE_REFERENCE_MISMATCH,
                 EVIDENCE_DUPLICATE_DOCUMENT, EVIDENCE_EXTRACTION_UNCERTAIN -> {
                // The payment side is deliberately the clean case. An evidence fault must not
                // corrupt the money side, so the three reconciliation methods also receive this
                // pack and are scored for false positives on it.
                orders.add(order(orderId, providerOrderId, amount, OrderState.PAID, OrderState.PAID, now.minusSeconds(600), amount));
                payments.add(payment(paymentId, orderId, providerPaymentId, amount, PaymentState.CAPTURED, PaymentState.CAPTURED, now.minusSeconds(300)));
                observations.add(observation(orderId, ResourceType.ORDER, providerOrderId, EvidenceSource.PROVIDER_API_CONFIRMED, OrderState.PAID.name(), amount, now.minusSeconds(300)));
                observations.add(observation(paymentId, ResourceType.PAYMENT, providerPaymentId, EvidenceSource.PROVIDER_API_CONFIRMED, PaymentState.CAPTURED.name(), amount, now.minusSeconds(300)));
                webhooks.add(webhook("evt_evidence_" + repetition, "payment.captured", now.minusSeconds(280), false));

                evidenceInput = evidenceInputFor(kind, repetition, orderId, providerOrderId, amount);
                required = evidenceOracleRequired(kind);
                critical = evidenceOracleCritical(kind);
                description = evidenceDescription(kind);
            }
            default -> throw new IllegalStateException("Unhandled fault kind " + kind);
        }

        EvidencePack pack = new EvidencePack(WORKSPACE_ID, now, STALENESS, transfersEnabled, providerReachable,
                orders, payments, refunds, transfers, observations, transitions, webhooks);

        String scenarioId = kind.name() + "-" + repetition + (variantB ? "-B" : "-A");
        return new BenchmarkScenario(scenarioId, kind, variantB, pack, evidenceInput,
                required, acceptable, critical, description);
    }

    // ---------------------------------------------------------------- evidence

    /** Tolerance used by the evidence scenarios' checks; deliberately small so injected faults exceed it. */
    private static final long EVIDENCE_TOLERANCE_MINOR = 100L;

    /**
     * Builds the paperwork view for one evidence fault kind.
     *
     * <p>The clean invoice is reused as the base and the fault is injected by changing exactly the
     * field the fault is about, so a scenario never declares one fault while accidentally injecting
     * a second one.</p>
     */
    private EvidenceCheckService.Input evidenceInputFor(FaultKind kind, int repetition, UUID orderId,
                                                        String providerOrderId, long amount) {
        UUID invoiceId = UUID.nameUUIDFromBytes((kind + ":invoice:" + repetition).getBytes());
        UUID shippingId = UUID.nameUUIDFromBytes((kind + ":shipping:" + repetition).getBytes());
        String internalReference = "ORD-" + orderId.toString().substring(0, 8);
        String invoiceHash = "hash-" + kind + repetition + "-invoice";

        EvidenceCheckService.DocumentFacts cleanInvoice = new EvidenceCheckService.DocumentFacts(
                invoiceId, "COMMERCIAL_INVOICE", EvidenceProcessingStatus.EXTRACTED,
                "INV-" + repetition, amount, "INR", internalReference, null,
                List.of(), List.of(), invoiceHash, null);
        EvidenceCheckService.DocumentFacts cleanShipping = new EvidenceCheckService.DocumentFacts(
                shippingId, "SHIPPING_BILL", EvidenceProcessingStatus.EXTRACTED,
                null, null, null, null, null,
                List.of(), List.of(), "hash-" + kind + repetition + "-shipping", null);

        List<EvidenceCheckService.DocumentFacts> documents = switch (kind) {
            case EVIDENCE_CLEAN -> List.of(cleanInvoice, cleanShipping);
            case EVIDENCE_MISSING_INVOICE -> List.of(cleanShipping);
            case EVIDENCE_UNREADABLE_DOCUMENT -> List.of(
                    new EvidenceCheckService.DocumentFacts(
                            invoiceId, "COMMERCIAL_INVOICE", EvidenceProcessingStatus.FAILED,
                            null, null, null, null, null,
                            List.of(), List.of(), invoiceHash, "the stored file is corrupt"),
                    cleanShipping);
            case EVIDENCE_AMOUNT_MISMATCH -> List.of(
                    new EvidenceCheckService.DocumentFacts(
                            invoiceId, "COMMERCIAL_INVOICE", EvidenceProcessingStatus.EXTRACTED,
                            "INV-" + repetition, amount + 5_000L, "INR", internalReference, null,
                            List.of(), List.of(), invoiceHash, null),
                    cleanShipping);
            case EVIDENCE_CURRENCY_MISMATCH -> List.of(
                    new EvidenceCheckService.DocumentFacts(
                            invoiceId, "COMMERCIAL_INVOICE", EvidenceProcessingStatus.EXTRACTED,
                            "INV-" + repetition, amount, "USD", internalReference, null,
                            List.of(), List.of(), invoiceHash, null),
                    cleanShipping);
            case EVIDENCE_REFERENCE_MISMATCH -> List.of(
                    new EvidenceCheckService.DocumentFacts(
                            invoiceId, "COMMERCIAL_INVOICE", EvidenceProcessingStatus.EXTRACTED,
                            "INV-" + repetition, amount, "INR", "OTHER-ORDER-" + repetition, null,
                            List.of(), List.of(), invoiceHash, null),
                    cleanShipping);
            case EVIDENCE_DUPLICATE_DOCUMENT -> List.of(cleanInvoice,
                    // Same bytes as the invoice but a different document type: one redundant record.
                    new EvidenceCheckService.DocumentFacts(
                            shippingId, "SHIPPING_BILL", EvidenceProcessingStatus.EXTRACTED,
                            null, null, null, null, null,
                            List.of(), List.of(), invoiceHash, null));
            case EVIDENCE_EXTRACTION_UNCERTAIN -> List.of(
                    new EvidenceCheckService.DocumentFacts(
                            invoiceId, "COMMERCIAL_INVOICE", EvidenceProcessingStatus.EXTRACTED,
                            "INV-" + repetition, amount, "INR", internalReference, null,
                            List.of(EvidenceFieldExtractor.FIELD_TOTAL, EvidenceFieldExtractor.FIELD_CURRENCY),
                            List.of(), invoiceHash, null),
                    cleanShipping);
            default -> throw new IllegalStateException("Unhandled evidence fault kind " + kind);
        };

        return new EvidenceCheckService.Input(
                new EvidenceCheckService.OrderFacts(orderId, internalReference, providerOrderId, amount, "INR"),
                documents,
                List.of("COMMERCIAL_INVOICE", "SHIPPING_BILL"),
                EVIDENCE_TOLERANCE_MINOR);
    }

    private static Set<FindingType> evidenceOracleRequired(FaultKind kind) {
        return switch (kind) {
            case EVIDENCE_CLEAN -> Set.of();
            case EVIDENCE_MISSING_INVOICE -> Set.of(FindingType.MISSING_REQUIRED_EVIDENCE);
            case EVIDENCE_UNREADABLE_DOCUMENT -> Set.of(FindingType.EVIDENCE_UNREADABLE);
            case EVIDENCE_AMOUNT_MISMATCH -> Set.of(FindingType.EVIDENCE_AMOUNT_MISMATCH);
            case EVIDENCE_CURRENCY_MISMATCH -> Set.of(FindingType.EVIDENCE_CURRENCY_MISMATCH);
            case EVIDENCE_REFERENCE_MISMATCH -> Set.of(FindingType.EVIDENCE_REFERENCE_MISMATCH);
            case EVIDENCE_DUPLICATE_DOCUMENT -> Set.of(FindingType.DUPLICATE_EVIDENCE_DOCUMENT);
            case EVIDENCE_EXTRACTION_UNCERTAIN -> Set.of(FindingType.EVIDENCE_EXTRACTION_UNCERTAIN);
            default -> throw new IllegalStateException("Not an evidence fault kind: " + kind);
        };
    }

    /**
     * The subset whose absence would be operationally dangerous.
     *
     * <p>A missing, unreadable or self-contradictory document blocks the dossier the payment needs.
     * The other three are review requests: an uncertain value or a foreign reference is a reason to
     * look, not a discrepancy that moves money, so missing one is not "incorrectly cleared".</p>
     */
    private static Set<FindingType> evidenceOracleCritical(FaultKind kind) {
        return switch (kind) {
            case EVIDENCE_MISSING_INVOICE, EVIDENCE_UNREADABLE_DOCUMENT,
                 EVIDENCE_AMOUNT_MISMATCH, EVIDENCE_CURRENCY_MISMATCH -> evidenceOracleRequired(kind);
            default -> Set.of();
        };
    }

    private static String evidenceDescription(FaultKind kind) {
        return switch (kind) {
            case EVIDENCE_CLEAN -> "Everything agrees and all required paperwork is held; any finding here is a false positive";
            case EVIDENCE_MISSING_INVOICE -> "A captured payment with a shipping bill but no commercial invoice";
            case EVIDENCE_UNREADABLE_DOCUMENT -> "The invoice is held but unreadable, which is not the same as absent";
            case EVIDENCE_AMOUNT_MISMATCH -> "The invoice total disagrees with the amount the order expects";
            case EVIDENCE_CURRENCY_MISMATCH -> "The invoice is denominated in a currency the order is not";
            case EVIDENCE_REFERENCE_MISMATCH -> "The invoice names an order reference that belongs to another order";
            case EVIDENCE_DUPLICATE_DOCUMENT -> "Two current documents are byte-identical";
            case EVIDENCE_EXTRACTION_UNCERTAIN -> "Values the checks need were read below the threshold and never confirmed";
            default -> throw new IllegalStateException("Not an evidence fault kind: " + kind);
        };
    }

    private static EvidencePack.OrderView order(UUID orderId, String providerOrderId, long amount,
                                                OrderState internalState, OrderState providerState,
                                                Instant createdAt, Long lastObservedAmount) {
        return new EvidencePack.OrderView(orderId, "ORD-" + orderId.toString().substring(0, 8),
                providerOrderId, amount, "INR", internalState, providerState, createdAt,
                lastObservedAmount == null ? null : createdAt, false);
    }

    private static EvidencePack.PaymentView payment(UUID paymentId, UUID orderId, String providerPaymentId,
                                                    long amount, PaymentState internalState, PaymentState providerState,
                                                    Instant observedAt) {
        return new EvidencePack.PaymentView(paymentId, orderId, providerPaymentId, amount, "INR",
                internalState, providerState, EvidenceSource.INTERNAL_RECORD.rank(), observedAt, observedAt, false);
    }

    private static EvidencePack.ObservationView observation(UUID entityId, ResourceType type, String providerResourceId,
                                                            EvidenceSource source, String state, long amount, Instant observedAt) {
        return new EvidencePack.ObservationView(entityId, type, providerResourceId, source, source.rank(),
                state, amount, "INR", observedAt, false);
    }

    private static EvidencePack.TransitionView transition(ResourceType type, UUID entityId, String from, String to,
                                                          boolean conflict, Instant occurredAt) {
        return new EvidencePack.TransitionView(type, entityId, from, to, conflict, true, occurredAt,
                "Observation contradicted the recorded terminal state and was not applied");
    }

    private static EvidencePack.WebhookObservation webhook(String eventId, String eventType, Instant receivedAt,
                                                           boolean repeat) {
        return new EvidencePack.WebhookObservation(eventId, eventType, eventId, null, receivedAt, receivedAt, repeat);
    }
}
