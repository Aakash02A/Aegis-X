package com.aegisx.integrity.benchmark;

import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.evidence.EvidenceCheckService;
import com.aegisx.integrity.reconciliation.model.EvidencePack;

import java.util.Set;

/**
 * One generated scenario plus its oracle.
 *
 * <p>The oracle is deliberately conservative and declared in advance: {@code required} lists the
 * finding types the scenario genuinely contains, and {@code acceptable} lists types that are also
 * legitimately reportable for that scenario without counting as noise. Anything a method reports
 * outside those two sets is a false positive.</p>
 *
 * <p>{@code criticalRequired} marks the subset whose absence would be operationally dangerous (a
 * discrepancy that actually moves money). A method that misses every critical finding has
 * "incorrectly cleared" the scenario, which is reported separately from ordinary recall.</p>
 */
public record BenchmarkScenario(
        String scenarioId,
        FaultKind kind,
        boolean variantB,
        EvidencePack pack,
        EvidenceCheckService.Input evidenceInput,
        Set<FindingType> required,
        Set<FindingType> acceptable,
        Set<FindingType> criticalRequired,
        String description) {

    public BenchmarkScenario {
        required = required == null ? Set.of() : Set.copyOf(required);
        acceptable = acceptable == null ? Set.of() : Set.copyOf(acceptable);
        criticalRequired = criticalRequired == null ? Set.of() : Set.copyOf(criticalRequired);
    }

    /**
     * The paperwork view of this scenario, or {@code null} for a payment-lifecycle fault.
     *
     * <p>Evidence fault kinds carry both views on purpose: the {@link #pack()} is a <em>clean</em>
     * payment pack, so the three reconciliation methods are scored for false positives against a
     * scenario whose only fault is on the paperwork side. An evidence fault must never corrupt the
     * money side, and the benchmark checks that rather than assuming it.</p>
     */
    public boolean hasEvidenceInput() {
        return evidenceInput != null;
    }
}
