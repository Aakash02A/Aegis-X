package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.ExceptionSeverity;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.FindingType;
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
 * Module F: an exception case an operator can work.
 *
 * <p>An exception is identified by {@code (workspaceId, findingType, entity)}. Repeated runs
 * refresh the same case rather than creating duplicates, increment {@code detectionCount}, and
 * reopen a resolved case if the discrepancy is still observable.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_exceptions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pi_exceptions_dedupe", columnNames = {"workspace_id", "dedupe_key"})
        })
public class ExceptionCase extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /**
     * Stable identity of the case: {@code <FINDING_TYPE>|<entity uuid or resource ref>}.
     *
     * <p>Derived rather than constructed from four nullable id columns so the database can enforce
     * one open case per issue with a single unique constraint.</p>
     */
    @Column(name = "dedupe_key", nullable = false, length = 160)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 50)
    private FindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ExceptionSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExceptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "certainty", nullable = false, length = 20)
    private CertaintyLevel certainty;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "transfer_id")
    private UUID transferId;

    /** Provider-facing reference of the affected resource, if any. */
    @Column(name = "provider_resource_ref", length = 120)
    private String providerResourceRef;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** The inferred diagnosis, expressed as a hypothesis, never as a confirmed financial outcome. */
    @Column(name = "diagnosis", nullable = false, length = 1000)
    private String diagnosis;

    @Column(name = "supporting_evidence_json", columnDefinition = "TEXT")
    private String supportingEvidenceJson;

    @Column(name = "missing_evidence_json", columnDefinition = "TEXT")
    private String missingEvidenceJson;

    @Column(name = "recommended_action", length = 500)
    private String recommendedAction;

    @Column(name = "first_detected_run_id")
    private UUID firstDetectedRunId;

    @Column(name = "last_seen_run_id")
    private UUID lastSeenRunId;

    @Builder.Default
    @Column(name = "detection_count", nullable = false)
    private int detectionCount = 1;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolution_summary", length = 1000)
    private String resolutionSummary;

    @Column(name = "resolution_evidence_json", columnDefinition = "TEXT")
    private String resolutionEvidenceJson;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** True when this case exists only because the provider was unreachable during detection. */
    @Builder.Default
    @Column(name = "provider_unreachable", nullable = false)
    private boolean providerUnreachable = false;
}
