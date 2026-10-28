package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.TransferState;
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
 * Route transfer registry. Only populated when the provider account has Route enabled; when it is
 * not, the reconciliation engine suppresses transfer findings with an explicit
 * "capability unavailable" evidence entry rather than reporting phantom mismatches.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_transfers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pi_transfers_provider_transfer", columnNames = {"workspace_id", "provider_transfer_id"})
        })
public class TransferRecord extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "provider_transfer_id", length = 100)
    private String providerTransferId;

    @Column(name = "linked_account_id", length = 100)
    private String linkedAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_state", nullable = false, length = 30)
    private TransferState providerState;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private TransferState state;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "last_observed_at")
    private Instant lastObservedAt;
}
