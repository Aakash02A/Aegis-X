package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Module H: append-only audit event for the integrity module.
 *
 * <p>The existing {@code audit_events} table requires a {@code transaction_id} foreign key into the
 * compliance domain, which the integrity engine does not have. This table is therefore the
 * integrity module's own append-only trail. Anti-update/delete behaviour is enforced by the
 * application, not by the database: Aegis-X does <strong>not</strong> claim that this trail is
 * cryptographically tamper-proof.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_audit_events")
public class IntegrityAuditEvent extends BaseEntity {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "actor_id")
    private UUID actorId;

    /** Human-readable actor label: role name, {@code SYSTEM}, {@code WEBHOOK}, {@code SIMULATOR}. */
    @Column(name = "actor_label", nullable = false, length = 120)
    private String actorLabel;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "exception_id")
    private UUID exceptionId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Builder.Default
    @Column(name = "simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
