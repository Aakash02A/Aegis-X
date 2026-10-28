package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.PaymentState;
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

/**
 * Module B: payment registry.
 *
 * <p>Holds both the provider-confirmed state and Aegis-X's internal view so the two can be
 * compared during reconciliation. {@code internalState} is never silently overwritten from a
 * provider webhook: transitions go through {@code PaymentLifecycleStateMachine}.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pi_payments_provider_payment", columnNames = {"workspace_id", "provider_payment_id"})
        })
public class PaymentRecord extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    @Column(name = "method", length = 40)
    private String method;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Provider-confirmed state, updated only from provider evidence. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_state", nullable = false, length = 30)
    private PaymentState providerState;

    /** Aegis-X canonical state, produced only by the state machine. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private PaymentState state;

    /** Rank of the evidence that last justified {@code state}. */
    @Column(name = "state_evidence_rank", nullable = false)
    private int stateEvidenceRank;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "first_observed_at")
    private Instant firstObservedAt;

    @Column(name = "last_observed_at")
    private Instant lastObservedAt;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;
}
