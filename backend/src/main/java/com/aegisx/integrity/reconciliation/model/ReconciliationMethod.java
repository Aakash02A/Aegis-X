package com.aegisx.integrity.reconciliation.model;

import com.aegisx.integrity.domain.ReconciliationStrategy;

import java.util.List;

/**
 * One reconciliation decision procedure.
 *
 * <p>Implementations must be pure: given the same {@link EvidencePack} they must return the same
 * findings, and they must not read the database. Determinism is what makes the benchmark
 * reproducible.</p>
 */
public interface ReconciliationMethod {

    ReconciliationStrategy strategy();

    /** Human-readable name used in benchmark output and on the reconciliation screen. */
    String displayName();

    List<FindingCandidate> evaluate(EvidencePack pack);
}
