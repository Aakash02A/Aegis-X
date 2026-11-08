package com.aegisx.review.dto;

import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.transaction.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for compliance review information.
 * Used in REST API responses to provide review details to clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceReviewResponse {

    private UUID id;
    private UUID transactionId;
    private String transactionReference;
    private UUID reviewerId;
    private String reviewerName;
    private TransactionStatus previousState;
    private TransactionStatus resultingState;
    private ReviewDecision decision;
    private String justification;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    // Additional context for UI
    private String decisionDisplayName;
    private String timeAgo;
    private boolean canBeReversed;

    /**
     * Creates response DTO from entity.
     */
    public static ComplianceReviewResponse fromEntity(ComplianceReview review) {
        ComplianceReviewResponseBuilder builder = ComplianceReviewResponse.builder()
                .id(review.getId())
                .transactionId(review.getTransaction().getId())
                .transactionReference(review.getTransaction().getReferenceNumber())
                .reviewerId(review.getReviewer().getId())
                .reviewerName(review.getReviewer().getFullName())
                .previousState(review.getPreviousState())
                .resultingState(review.getTransaction().getStatus())
                .decision(review.getDecision())
                .justification(review.getJustification())
                .reviewedAt(review.getReviewedAt())
                .createdAt(java.time.LocalDateTime.ofInstant(review.getCreatedAt(), java.time.ZoneOffset.UTC))
                .decisionDisplayName(review.getDecision().getDisplayName())
                .canBeReversed(false); // Reviews are typically final

        return builder.build();
    }

    /**
     * Creates response with additional transaction context.
     */
    public static ComplianceReviewResponse fromEntityWithContext(ComplianceReview review, String timeAgo) {
        ComplianceReviewResponse response = fromEntity(review);
        response.setTimeAgo(timeAgo);
        return response;
    }

    /**
     * Returns true if this review resulted in approval.
     */
    public boolean isApproval() {
        return decision == ReviewDecision.APPROVED;
    }

    /**
     * Returns true if this review resulted in rejection.
     */
    public boolean isRejection() {
        return decision == ReviewDecision.REJECTED;
    }

    /**
     * Gets a summary of the review for display purposes.
     */
    public String getReviewSummary() {
        String summary = String.format("%s by %s", 
                decision.getDisplayName(), 
                reviewerName);
        
        if (timeAgo != null) {
            summary += " (" + timeAgo + ")";
        }
        
        return summary;
    }

    /**
     * Gets truncated justification for list views.
     */
    public String getTruncatedJustification(int maxLength) {
        if (justification == null) {
            return "";
        }
        
        if (justification.length() <= maxLength) {
            return justification;
        }
        
        return justification.substring(0, maxLength) + "...";
    }
}
