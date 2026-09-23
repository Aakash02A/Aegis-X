package com.aegisx.review.entity;

import com.aegisx.auth.entity.User;
import com.aegisx.transaction.entity.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable compliance officer investigation note on a transaction.
 * 
 * Each note represents a step in the compliance review process:
 * - Investigation findings
 * - Verification steps taken
 * - Risk assessments
 * - Recommendations
 * - Follow-up items
 * 
 * Notes are append-only and automatically create audit events.
 * This ensures complete traceability of the compliance review workflow.
 * 
 * Example workflow:
 * 1. Compliance officer opens transaction
 * 2. Reviews uploaded documents
 * 3. Adds note: "Verified merchant identity via phone call"
 * 4. Reviews AI findings
 * 5. Adds note: "High velocity but within acceptable range for new merchant"
 * 6. Reviews policy engine results
 * 7. Adds note: "Approved. All controls satisfied. Ready for settlement."
 * 
 * Each note creation triggers MANUAL_REVIEW_NOTE_ADDED audit event,
 * creating permanent record of compliance investigation steps.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "manual_review_notes",
    indexes = {
        @Index(name = "idx_review_notes_transaction_id", columnList = "transaction_id"),
        @Index(name = "idx_review_notes_user_id", columnList = "user_id"),
        @Index(name = "idx_review_notes_created_at", columnList = "created_at"),
        @Index(name = "idx_review_notes_transaction_user", columnList = "transaction_id, created_at DESC")
    }
)
public class ManualReviewNote {

    /**
     * Unique identifier for this note.
     * Generated as UUID, immutable.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Foreign key to the transaction this note belongs to.
     * Every note is linked to exactly one transaction.
     * Cascade delete if transaction is removed.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "transaction_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_review_notes_transaction")
    )
    private Transaction transaction;

    /**
     * Foreign key to the user (compliance officer or admin) who wrote this note.
     * Tracks authorship for audit trail and RBAC enforcement.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_review_notes_user")
    )
    private User author;

    /**
     * Free-text investigation note content.
     * Can contain:
     * - Findings from document review
     * - Verification steps taken
     * - Risk assessment conclusions
     * - Policy evaluation notes
     * - Recommendations
     * - Follow-up items
     * - Questions for merchant
     * - Internal discussion points
     * 
     * No length limit - intended for detailed compliance analysis.
     */
    @Column(name = "note_text", nullable = false, columnDefinition = "TEXT")
    private String noteText;

    /**
     * Immutable timestamp when note was created.
     * Used for chronological ordering in compliance workspace
     * and audit timeline.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Lifecycle hook to ensure immutability and generate defaults.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
