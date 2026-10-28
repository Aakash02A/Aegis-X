package com.aegisx.integrity.reconciliation.model;

import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.FindingType;

import java.util.List;
import java.util.UUID;

/** Small builder used by the rules-engine and proposed methods so candidates stay consistent. */
public final class CandidateFactory {

    private CandidateFactory() {
    }

    public static FindingCandidate of(FindingType type,
                                      CertaintyLevel certainty,
                                      UUID orderId, UUID paymentId, UUID refundId, UUID transferId,
                                      String entityKey,
                                      String summary,
                                      List<String> evidence,
                                      List<String> missingEvidence) {
        return new FindingCandidate(type, SeverityRules.of(type), certainty,
                orderId, paymentId, refundId, transferId, entityKey, summary, evidence, missingEvidence);
    }

    public static String describe(String label, Object value) {
        return label + " = " + value;
    }
}
