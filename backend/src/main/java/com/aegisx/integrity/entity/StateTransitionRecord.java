package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.EvidenceSource;
import com.aegisx.integrity.domain.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Module D: canonical state-transition history.
 *
 * <p>Every accepted transition is recorded here with the evidence that justified it. A transition
 * that was observed but rejected (because it contradicted a higher-ranked terminal state) is also
 * recorded with {@code conflict = true}. That is what allows the proposed method to explain
 * <em>why</em> a state is what it is, rather than only reporting the latest received value.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_state_transitions")
public class StateTransitionRecord extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private ResourceType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "from_state", length = 30)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 30)
    private String toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_source", nullable = false, length = 30)
    private EvidenceSource evidenceSource;

    @Column(name = "evidence_rank", nullable = false)
    private int evidenceRank;

    /** True only when the transition was justified by provider-authoritative evidence. */
    @Column(name = "provider_confirmed", nullable = false)
    private boolean providerConfirmed;

    @Column(name = "actor", nullable = false, length = 60)
    private String actor;

    @Column(name = "reason", length = 500)
    private String reason;

    /** True when this observation contradicted the current state and was recorded but not applied. */
    @Builder.Default
    @Column(name = "conflict", nullable = false)
    private boolean conflict = false;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
