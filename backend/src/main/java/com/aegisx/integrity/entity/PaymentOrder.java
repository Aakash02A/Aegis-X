package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.OrderState;
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
 * Module B: internal order registry.
 *
 * <p>Amounts are stored as integer minor units ({@code BIGINT}). Floating-point money is never
 * used anywhere in this module.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pi_orders_reference", columnNames = "internal_reference"),
                @UniqueConstraint(name = "uq_pi_orders_provider_order", columnNames = {"workspace_id", "provider_order_id"})
        })
public class PaymentOrder extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Internal order id shown to operators, for example {@code ORD-4F2A91C3}. */
    @Column(name = "internal_reference", nullable = false, length = 64)
    private String internalReference;

    /** Provider order id ({@code order_...}). Null until the provider accepted the creation. */
    @Column(name = "provider_order_id", length = 100)
    private String providerOrderId;

    @Column(name = "expected_amount_minor", nullable = false)
    private long expectedAmountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    /** Provider-confirmed order state, or null when no provider order exists yet. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_state", length = 30)
    private OrderState providerState;

    /** Aegis-X canonical state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private OrderState state;

    /** True when this row was produced with the simulated adapter. Surfaced in every DTO and the UI. */
    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_by")
    private UUID createdBy;

    /** When the highest-ranked observation of this order was recorded. */
    @Column(name = "last_observed_at")
    private Instant lastObservedAt;
}
