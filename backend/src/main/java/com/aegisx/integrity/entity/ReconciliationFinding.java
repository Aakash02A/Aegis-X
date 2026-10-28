package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.ExceptionSeverity;
import com.aegisx.integrity.domain.FindingType;
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

import java.util.UUID;

/**
 * Module E: one persisted discrepancy produced by a reconciliation run.
 *
 * <p>Evidence and missing evidence are stored explicitly so the operator sees exactly what the
 * classification rests on, and what a provider lookup could add.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_reconciliation_findings")
public class ReconciliationFinding extends BaseEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

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
    @Column(name = "finding_type", nullable = false, length = 50)
    private FindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ExceptionSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "certainty", nullable = false, length = 20)
    private CertaintyLevel certainty;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    /** JSON array of human-readable evidence statements, each tagged with its source rank. */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    /** JSON array of statements describing evidence that is absent and would help. */
    @Column(name = "missing_evidence_json", columnDefinition = "TEXT")
    private String missingEvidenceJson;

    /** Set when this finding was attached to (or created) an exception case. */
    @Column(name = "exception_id")
    private UUID exceptionId;
}
