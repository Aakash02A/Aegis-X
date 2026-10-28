package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.ReconciliationTrigger;
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

/** One reconciliation pass over a workspace, with the counts that produced its findings. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_reconciliation_runs")
public class ReconciliationRun extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private ReconciliationTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 40)
    private ReconciliationStrategy strategy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Builder.Default
    @Column(name = "orders_checked", nullable = false)
    private int ordersChecked = 0;

    @Builder.Default
    @Column(name = "payments_checked", nullable = false)
    private int paymentsChecked = 0;

    @Builder.Default
    @Column(name = "refunds_checked", nullable = false)
    private int refundsChecked = 0;

    @Builder.Default
    @Column(name = "transfers_checked", nullable = false)
    private int transfersChecked = 0;

    /** Evidence documents examined. Non-zero only for an evidence assessment run. */
    @Builder.Default
    @Column(name = "evidence_documents_checked", nullable = false)
    private int documentsChecked = 0;

    @Builder.Default
    @Column(name = "findings_count", nullable = false)
    private int findingsCount = 0;

    @Builder.Default
    @Column(name = "new_exceptions", nullable = false)
    private int newExceptions = 0;

    @Builder.Default
    @Column(name = "reopened_exceptions", nullable = false)
    private int reopenedExceptions = 0;

    @Builder.Default
    @Column(name = "refreshed_exceptions", nullable = false)
    private int refreshedExceptions = 0;

    /** False when the provider could not be reached, so findings rest on stored evidence only. */
    @Column(name = "provider_reachable", nullable = false)
    private boolean providerReachable;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "triggered_by")
    private UUID triggeredBy;
}
