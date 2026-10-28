package com.aegisx.integrity.recovery;

import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationType;
import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Module G: maps a detected discrepancy to one or more classified recovery actions.
 *
 * <p>The policy is the safety boundary. It is deterministic, has no side effects, and is the single
 * place that decides whether an action is {@code READ_ONLY}, {@code HUMAN_APPROVAL_REQUIRED} or
 * {@code FINANCIAL}.</p>
 *
 * <p>Two financial actions exist and both are strictly guarded: {@code CREATE_REFUND} is only
 * raised when the provider still reports the payment as captured (so the refund Aegis-X recorded
 * evidently never happened on the provider side), and {@code CREATE_TRANSFER} only when a captured
 * payment has no transfer. Neither is ever executed automatically.</p>
 */
@Component
public class RecommendationPolicy {

    private static final String TIMEOUT_NOTE =
            "Never repeat a provider call just because a previous attempt timed out: the request may already "
                    + "have succeeded. Always read the provider's current state first.";

    /** Primary recommendation plus any justified secondary action. */
    public List<RecommendationPlan> planFor(FindingType type, EvidencePack pack) {
        List<RecommendationPlan> plans = new ArrayList<>();
        plans.add(primary(type, pack));
        secondary(type, pack).ifPresent(plans::add);
        return List.copyOf(plans);
    }

    public RecommendationPlan primary(FindingType type, EvidencePack pack) {
        return switch (type) {
            case INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT -> RecommendationPlan.readOnly(
                    RecommendationType.FETCH_PROVIDER_STATE,
                    "Read the provider's current order and payment state before assuming the payment never happened. "
                            + TIMEOUT_NOTE);
            case PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER -> new RecommendationPlan(
                    RecommendationType.ESCALATE_TO_HUMAN,
                    RecommendationCategory.HUMAN_APPROVAL_REQUIRED,
                    "A provider payment exists that Aegis-X cannot map to an order. Mapping it is a business decision, "
                            + "not a lookup, so it needs a human.",
                    "Linking the wrong payment to an order would corrupt the ledger for both records.",
                    true);
            case AMOUNT_MISMATCH, CURRENCY_MISMATCH -> RecommendationPlan.readOnly(
                    RecommendationType.INVESTIGATE_AMOUNT_MISMATCH,
                    "Compare the provider-confirmed amount and currency with the internal expected value, including any "
                            + "fees, before deciding whether the internal record or the provider record is wrong.");
            case STATUS_MISMATCH -> RecommendationPlan.readOnly(
                    RecommendationType.FETCH_PROVIDER_STATE,
                    "Fetch the provider's current state for the affected resource. Provider-confirmed state outranks the "
                            + "internal record, but only after it is re-read. " + TIMEOUT_NOTE);
            case DUPLICATE_EVENT_DELIVERY -> RecommendationPlan.readOnly(
                    RecommendationType.NO_ACTION_REQUIRED,
                    "The provider documents at-least-once delivery, so a repeat is expected. The duplicate was already "
                            + "discarded by the ingestion deduplication guard; no business action is needed.");
            case STALE_PAYMENT -> RecommendationPlan.readOnly(
                    RecommendationType.WAIT_FOR_PENDING,
                    "The operation may still be in flight. Observe it after the next reconciliation cycle rather than "
                            + "acting on an incomplete state. " + TIMEOUT_NOTE);
            case REFUND_DISCREPANCY -> RecommendationPlan.readOnly(
                    RecommendationType.INSPECT_REFUND,
                    "Inspect the provider's refund records for this payment and compare them with the internal refunds "
                            + "before taking any action.");
            case TRANSFER_DISCREPANCY -> RecommendationPlan.readOnly(
                    RecommendationType.INSPECT_TRANSFER,
                    "Inspect the provider's transfer records for this payment. Route must be enabled for the account "
                            + "before any transfer state can be trusted.");
            case CONFLICTING_OBSERVATIONS -> RecommendationPlan.readOnly(
                    RecommendationType.FETCH_PROVIDER_STATE,
                    "Two observations disagree. Re-read the provider's current state to settle which one is current; the "
                            + "conflict itself is already recorded in the transition history.");

            // ------------------------------------------------------------ evidence

            case MISSING_REQUIRED_EVIDENCE -> new RecommendationPlan(
                    RecommendationType.REQUEST_MISSING_DOCUMENT,
                    RecommendationCategory.HUMAN_APPROVAL_REQUIRED,
                    "Request the missing supporting document from the party that is expected to hold it. The payment "
                            + "itself may be perfectly successful; it is the record that is incomplete.",
                    "Do not treat a missing invoice as a failed payment, and do not reverse a captured payment to "
                            + "resolve a paperwork gap.",
                    true);
            case EVIDENCE_AMOUNT_MISMATCH -> RecommendationPlan.readOnly(
                    RecommendationType.COMPARE_INVOICE_TOTAL,
                    "Compare the total stated on the document with the order's expected amount and the amount the "
                            + "provider actually captured, including tax, discounts and partial payments, before changing "
                            + "either record.");
            case EVIDENCE_CURRENCY_MISMATCH -> RecommendationPlan.readOnly(
                    RecommendationType.COMPARE_INVOICE_TOTAL,
                    "The document and the order are denominated differently. Establish which currency the obligation "
                            + "was actually placed in rather than assuming the document is wrong.");
            case EVIDENCE_REFERENCE_MISMATCH -> RecommendationPlan.readOnly(
                    RecommendationType.VERIFY_DOCUMENT_ASSOCIATION,
                    "The document names an order or payment reference that is not this one. Confirm the document "
                            + "belongs to this payment before relying on any value read from it.");
            case DUPLICATE_EVIDENCE_DOCUMENT -> RecommendationPlan.readOnly(
                    RecommendationType.RESOLVE_DUPLICATE_DOCUMENT,
                    "Two current documents carry the same content or the same invoice number. Decide which one is "
                            + "authoritative; Aegis-X will not choose for you.");
            case EVIDENCE_EXTRACTION_UNCERTAIN -> new RecommendationPlan(
                    RecommendationType.CONFIRM_EXTRACTED_VALUES,
                    RecommendationCategory.HUMAN_APPROVAL_REQUIRED,
                    "Open the document and confirm or correct the flagged values. Matching cannot be trusted while a "
                            + "value it depends on was read below the confidence threshold.",
                    "An unconfirmed value must not be treated as an established fact, and successful extraction is not "
                            + "evidence that a document is genuine.",
                    true);
            case EVIDENCE_UNREADABLE -> new RecommendationPlan(
                    RecommendationType.REQUEST_LEGIBLE_COPY,
                    RecommendationCategory.HUMAN_APPROVAL_REQUIRED,
                    "The document could not be read, so its contents are unknown rather than absent. Re-read it, or ask "
                            + "for a legible copy.",
                    "Do not record a document as missing when it was merely unreadable: the two need different follow-up.",
                    true);
            case EVIDENCE_ORPHANED -> RecommendationPlan.readOnly(
                    RecommendationType.VERIFY_DOCUMENT_ASSOCIATION,
                    "A document is attached to an order that no longer exists in this workspace, so it cannot justify "
                            + "any payment. Confirm the correct association before using it.");
        };
    }

    private Optional<RecommendationPlan> secondary(FindingType type, EvidencePack pack) {
        if (type != FindingType.REFUND_DISCREPANCY && type != FindingType.TRANSFER_DISCREPANCY) {
            return Optional.empty();
        }
        boolean providerStillHoldsFunds = pack.payments().stream()
                .map(payment -> providerState(pack, payment))
                .anyMatch(state -> state == PaymentState.CAPTURED);
        if (!providerStillHoldsFunds) {
            return Optional.empty();
        }
        if (type == FindingType.REFUND_DISCREPANCY) {
            return Optional.of(new RecommendationPlan(
                    RecommendationType.CREATE_REFUND,
                    RecommendationCategory.FINANCIAL,
                    "The provider still reports this payment as captured, which suggests the refund Aegis-X recorded did "
                            + "not actually happen. A refund may need to be created.",
                    "This moves money. It must not run automatically, and it must not be retried blindly after a timeout: "
                            + "read the provider's refund list first, because a previous attempt may already have succeeded.",
                    true));
        }
        return Optional.of(new RecommendationPlan(
                RecommendationType.CREATE_TRANSFER,
                RecommendationCategory.FINANCIAL,
                "The payment is captured but no completed transfer is recorded; a Route transfer may be outstanding.",
                "This moves money to a linked account. It must not run automatically, and Route enablement must be "
                        + "confirmed first.",
                true));
    }

    private static PaymentState providerState(EvidencePack pack, EvidencePack.PaymentView payment) {
        return pack.observationsOf(ResourceType.PAYMENT, payment.paymentId()).stream()
                .filter(o -> o.source().isProviderAuthoritative())
                .map(o -> {
                    try {
                        return PaymentState.valueOf(o.state());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(payment.providerState());
    }
}
