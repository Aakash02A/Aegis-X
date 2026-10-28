package com.aegisx.review.entity;

import com.aegisx.auth.entity.User;
import com.aegisx.common.entity.BaseEntity;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Compliance Review Entity - human decision log for compliance officer manual reviews.
 * Implements the compliance reviews specification from TAS Section 6.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "compliance_reviews")
public class ComplianceReview extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", nullable = false, length = 40)
    private TransactionStatus previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 20)
    private ReviewDecision decision;

    @Column(name = "justification", nullable = false, columnDefinition = "TEXT")
    private String justification;

    /**
     * Structured review context.
     *
     * Mapped with Hibernate's own JSON type code and no {@code columnDefinition}: the
     * dialect then renders {@code jsonb} on PostgreSQL and {@code json} on MySQL, so one
     * entity mapping validates against both schemas. Pinning {@code columnDefinition}
     * (the previous {@code "JSONB"}) makes Hibernate expect PostgreSQL's type name on
     * MySQL and fails validation; hypersistence's {@code JsonType} instead reports
     * {@code SqlTypes.OTHER}, which MySQL has no column type for.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_context")
    private JsonNode reviewContext;

    @Column(name = "reviewed_at", nullable = false)
    private LocalDateTime reviewedAt;

    /**
     * Creates a new compliance review for a transaction.
     */
    public static ComplianceReview createReview(Transaction transaction, User reviewer, 
                                                TransactionStatus previousState, 
                                                ReviewDecision decision, String justification) {
        return ComplianceReview.builder()
                .transaction(transaction)
                .reviewer(reviewer)
                .previousState(previousState)
                .decision(decision)
                .justification(justification)
                .reviewedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Creates a new compliance review with additional context.
     */
    public static ComplianceReview createReviewWithContext(Transaction transaction, User reviewer,
                                                           TransactionStatus previousState, 
                                                           ReviewDecision decision, String justification,
                                                           JsonNode context) {
        ComplianceReview review = createReview(transaction, reviewer, previousState, decision, justification);
        review.setReviewContext(context);
        return review;
    }

    /**
     * Returns true if this review approved the transaction.
     */
    public boolean isApproval() {
        return decision == ReviewDecision.APPROVED;
    }

    /**
     * Returns true if this review rejected the transaction.
     */
    public boolean isRejection() {
        return decision == ReviewDecision.REJECTED;
    }

    /**
     * Validates that the review meets all requirements.
     */
    public void validateReview() {
        if (previousState != TransactionStatus.COMPLIANCE_HOLD) {
            throw new IllegalStateException("Reviews can only be performed on transactions in COMPLIANCE_HOLD state");
        }
        
        if (justification == null || justification.trim().length() < 10) {
            throw new IllegalArgumentException("Justification must be at least 10 characters long");
        }
        
        if (reviewer == null) {
            throw new IllegalArgumentException("Reviewer is required");
        }
    }

    /**
     * Gets a formatted summary of this review.
     */
    public String getReviewSummary() {
        return String.format("%s by %s on %s: %s", 
                decision.getDisplayName(), 
                reviewer.getFullName(), 
                reviewedAt.toLocalDate(), 
                justification.substring(0, Math.min(100, justification.length())));
    }
}