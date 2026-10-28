package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.EvidenceReadinessStatus;
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
 * Module I: the evidence-readiness dimension for one order.
 *
 * <p>Exists so that "the payment succeeded" and "the supporting records are complete" can be two
 * different answers at the same time, which is the case for most real payments on the day they are
 * taken. The row is upserted per order; the assessment that produced it is referenced by
 * {@code assessmentId} so the reason can always be traced back to a run.</p>
 *
 * <p>The {@link EvidenceReadinessStatus} entry/exit conditions are documented on that enum and are
 * applied by {@code EvidenceReadinessService} — this entity stores the conclusion, it does not
 * decide it.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "pi_evidence_readiness",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_pi_evidence_readiness_order", columnNames = {"workspace_id", "order_id"}))
public class EvidenceReadiness extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private EvidenceReadinessStatus status;

    /** Plain-language justification, shown verbatim to the operator. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** JSON array of required document type names. */
    @Column(name = "required_document_types", columnDefinition = "TEXT")
    private String requiredDocumentTypes;

    /** JSON array of required types that have no active document. */
    @Column(name = "missing_document_types", columnDefinition = "TEXT")
    private String missingDocumentTypes;

    /** JSON array of types whose values a human must confirm. */
    @Column(name = "uncertain_document_types", columnDefinition = "TEXT")
    private String uncertainDocumentTypes;

    /** How many evidence findings currently block completion. */
    @Builder.Default
    @Column(name = "blocking_findings", nullable = false)
    private int blockingFindings = 0;

    @Builder.Default
    @Column(name = "documents_present", nullable = false)
    private int documentsPresent = 0;

    @Builder.Default
    @Column(name = "documents_expected", nullable = false)
    private int documentsExpected = 0;

    /** The reconciliation run that produced this assessment. */
    @Column(name = "assessment_id")
    private UUID assessmentId;

    @Column(name = "last_assessed_at", nullable = false)
    private Instant lastAssessedAt;

    @Column(name = "assessed_by")
    private UUID assessedBy;
}
