package com.aegisx.integrity.reconciliation.model;

import com.aegisx.integrity.domain.ExceptionSeverity;
import com.aegisx.integrity.domain.FindingType;

import java.util.Map;

/**
 * The single severity mapping shared by every method.
 *
 * <p>Sharing it is deliberate: if each method assigned its own severities, benchmark differences in
 * "human review rate" would measure the severity tables rather than the detection quality.</p>
 *
 * <p>Evidence findings are ranked by how much they can mislead someone reading the payment record.
 * A total that disagrees with the order is {@code HIGH} because it changes whether the payment looks
 * correct. A missing document is {@code MEDIUM}: it is a routine state that needs chasing, not an
 * incident. An uncertain extraction is {@code LOW}, because an uncertain value that has been flagged
 * is still safer than a confident wrong one, and the flag is what the operator acts on.</p>
 */
public final class SeverityRules {

    private static final Map<FindingType, ExceptionSeverity> SEVERITY = Map.ofEntries(
            Map.entry(FindingType.AMOUNT_MISMATCH, ExceptionSeverity.HIGH),
            Map.entry(FindingType.CURRENCY_MISMATCH, ExceptionSeverity.HIGH),
            Map.entry(FindingType.PROVIDER_PAYMENT_WITHOUT_INTERNAL_ORDER, ExceptionSeverity.HIGH),
            Map.entry(FindingType.REFUND_DISCREPANCY, ExceptionSeverity.HIGH),
            Map.entry(FindingType.TRANSFER_DISCREPANCY, ExceptionSeverity.HIGH),
            Map.entry(FindingType.CONFLICTING_OBSERVATIONS, ExceptionSeverity.HIGH),
            Map.entry(FindingType.STATUS_MISMATCH, ExceptionSeverity.MEDIUM),
            Map.entry(FindingType.INTERNAL_ORDER_WITHOUT_PROVIDER_PAYMENT, ExceptionSeverity.MEDIUM),
            Map.entry(FindingType.STALE_PAYMENT, ExceptionSeverity.MEDIUM),
            Map.entry(FindingType.DUPLICATE_EVENT_DELIVERY, ExceptionSeverity.LOW),
            // evidence dimension
            Map.entry(FindingType.EVIDENCE_AMOUNT_MISMATCH, ExceptionSeverity.HIGH),
            Map.entry(FindingType.EVIDENCE_CURRENCY_MISMATCH, ExceptionSeverity.HIGH),
            Map.entry(FindingType.EVIDENCE_REFERENCE_MISMATCH, ExceptionSeverity.HIGH),
            Map.entry(FindingType.EVIDENCE_ORPHANED, ExceptionSeverity.HIGH),
            Map.entry(FindingType.MISSING_REQUIRED_EVIDENCE, ExceptionSeverity.MEDIUM),
            Map.entry(FindingType.DUPLICATE_EVIDENCE_DOCUMENT, ExceptionSeverity.MEDIUM),
            Map.entry(FindingType.EVIDENCE_UNREADABLE, ExceptionSeverity.MEDIUM),
            Map.entry(FindingType.EVIDENCE_EXTRACTION_UNCERTAIN, ExceptionSeverity.LOW));

    private SeverityRules() {
    }

    public static ExceptionSeverity of(FindingType type) {
        return SEVERITY.getOrDefault(type, ExceptionSeverity.MEDIUM);
    }
}
