package com.aegisx.review.dto;

import com.aegisx.review.entity.ReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for compliance officer review decisions.
 * Contains the decision and mandatory justification for audit trails.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceReviewRequest {

    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;

    @NotNull(message = "Review decision is required")
    private ReviewDecision decision;

    @NotNull(message = "Justification is required")
    @Size(min = 10, max = 2000, message = "Justification must be between 10 and 2000 characters")
    private String justification;

    private String reviewerComments;
    
    private Boolean flagForAudit;

    /**
     * Validates the review request.
     */
    public void validate() {
        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID cannot be null");
        }
        
        if (decision == null) {
            throw new IllegalArgumentException("Review decision cannot be null");
        }
        
        if (justification == null || justification.trim().length() < 10) {
            throw new IllegalArgumentException("Justification must be at least 10 characters long");
        }
        
        if (justification.length() > 2000) {
            throw new IllegalArgumentException("Justification cannot exceed 2000 characters");
        }
    }

    /**
     * Returns true if this is an approval decision.
     */
    public boolean isApproval() {
        return decision == ReviewDecision.APPROVED;
    }

    /**
     * Returns true if this is a rejection decision.
     */
    public boolean isRejection() {
        return decision == ReviewDecision.REJECTED;
    }

    /**
     * Gets the trimmed justification text.
     */
    public String getTrimmedJustification() {
        return justification != null ? justification.trim() : null;
    }

    /**
     * Returns true if this review should be flagged for audit.
     */
    public boolean shouldFlagForAudit() {
        return Boolean.TRUE.equals(flagForAudit);
    }
}