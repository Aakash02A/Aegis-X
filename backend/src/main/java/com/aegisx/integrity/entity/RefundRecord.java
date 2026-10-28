package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.RefundState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Refund registry. Refunds are only possible against a provider-captured payment. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_refunds",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pi_refunds_provider_refund", columnNames = {"workspace_id", "provider_refund_id"})
        })
public class RefundRecord extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "provider_refund_id", length = 100)
    private String providerRefundId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_state", nullable = false, length = 30)
    private RefundState providerState;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private RefundState state;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "last_observed_at")
    private Instant lastObservedAt;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;
}
