package com.aegisx.integrity.domain;

import java.time.Duration;

/**
 * Evidence hierarchy. Higher rank outranks lower rank when two observations disagree.
 *
 * <p>This ordering is the core of the proposed method: a value Aegis-X inferred, or a value the
 * simulated provider produced, can never override state the real provider confirmed.</p>
 */
public enum EvidenceSource {
    /** Rank 4: fetched live from the provider's own resource endpoint. */
    PROVIDER_API_CONFIRMED(4, Duration.ofMillis(1)),
    /** Rank 3: a signature-verified webhook observation. */
    PROVIDER_WEBHOOK(3, Duration.ofSeconds(1)),
    /** Rank 2: Aegis-X's own persisted record. */
    INTERNAL_RECORD(2, Duration.ofMillis(1)),
    /** Rank 1: derived by Aegis-X (for example "stale because older than the threshold"). */
    INFERRED(1, Duration.ofMillis(1)),
    /**
     * Rank 0: evidence that is synthetic by construction.
     *
     * <p>Note the distinction between <em>rank</em> and <em>provenance</em>. When the simulated
     * adapter stands in for a provider, its answers are recorded at the rank of the evidence they
     * model ({@link #PROVIDER_API_CONFIRMED} or {@link #PROVIDER_WEBHOOK}) and flagged
     * {@code simulated = true}, so the engine treats them exactly like provider answers while every
     * screen still labels them as simulated. This value is reserved for evidence that is
     * deliberately weaker than a provider statement, such as a benchmark fixture or an injected
     * projection.</p>
     */
    SIMULATED(0, Duration.ofMillis(1));

    private final int rank;
    private final Duration timePrecision;

    EvidenceSource(int rank, Duration timePrecision) {
        this.rank = rank;
        this.timePrecision = timePrecision;
    }

    public int rank() {
        return rank;
    }

    /**
     * Smallest timestamp separation at which this source supports an ordering claim.
     */
    public Duration timePrecision() {
        return timePrecision;
    }

    /** True when this source represents an actual provider (live API or verified webhook). */
    public boolean isProviderAuthoritative() {
        return this == PROVIDER_API_CONFIRMED || this == PROVIDER_WEBHOOK;
    }

    public boolean outranks(EvidenceSource other) {
        return this.rank > other.rank;
    }
}
