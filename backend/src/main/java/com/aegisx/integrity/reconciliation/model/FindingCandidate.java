package com.aegisx.integrity.reconciliation.model;

import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.ExceptionSeverity;
import com.aegisx.integrity.domain.FindingType;

import java.util.List;
import java.util.UUID;

/**
 * A discrepancy detected by a reconciliation method, before it becomes a persisted exception.
 *
 * <p>Evidence and missing evidence are carried explicitly so the explanation is a property of the
 * detection, not something reconstructed later from a log.</p>
 *
 * @param entityKey stable identity of the affected entity, used to deduplicate exception cases
 */
public record FindingCandidate(
        FindingType type,
        ExceptionSeverity severity,
        CertaintyLevel certainty,
        UUID orderId,
        UUID paymentId,
        UUID refundId,
        UUID transferId,
        String entityKey,
        String summary,
        List<String> evidence,
        List<String> missingEvidence) {

    public FindingCandidate {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
    }

    /** Case identity: one open exception per (type, entity). */
    public String dedupeKey() {
        return type.name() + "|" + (entityKey == null ? "workspace" : entityKey);
    }
}
