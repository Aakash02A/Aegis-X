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
 * A point-in-time observation of one provider (or internal) resource.
 *
 * <p>Observations are the raw material of the evidence hierarchy. Nothing is ever updated in
 * place: a new observation row supersedes an old one by rank and timestamp, so the history of
 * what was believed when remains inspectable.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_provider_observations")
public class ProviderObservation extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    @Column(name = "provider_resource_id", nullable = false, length = 100)
    private String providerResourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private EvidenceSource source;

    @Column(name = "evidence_rank", nullable = false)
    private int evidenceRank;

    @Column(name = "observed_state", nullable = false, length = 30)
    private String observedState;

    @Column(name = "observed_amount_minor")
    private Long observedAmountMinor;

    @Column(name = "observed_currency", length = 3)
    private String observedCurrency;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;
}
