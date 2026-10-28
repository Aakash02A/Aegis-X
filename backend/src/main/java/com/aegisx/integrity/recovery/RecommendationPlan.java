package com.aegisx.integrity.recovery;

import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationType;

/**
 * A proposed recovery action, before it is persisted.
 *
 * @param rationale             why this is the recommended next step
 * @param riskNote              what could go wrong; shown verbatim to the operator
 * @param requiresHumanApproval true for anything that is not purely read-only
 */
public record RecommendationPlan(
        RecommendationType type,
        RecommendationCategory category,
        String rationale,
        String riskNote,
        boolean requiresHumanApproval) {

    public static RecommendationPlan readOnly(RecommendationType type, String rationale) {
        return new RecommendationPlan(type, RecommendationCategory.READ_ONLY, rationale, null, false);
    }
}
