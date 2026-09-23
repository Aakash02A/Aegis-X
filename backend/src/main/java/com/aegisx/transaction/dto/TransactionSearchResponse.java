package com.aegisx.transaction.dto;

import com.aegisx.review.repository.ManualReviewNoteRepository;
import com.aegisx.transaction.entity.Transaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for transaction search results.
 * Includes summary information and counts for quick dashboard display.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionSearchResponse {

    /**
     * Unique identifier for transaction.
     */
    private UUID id;

    /**
     * Transaction reference number (e.g., "TXN-20260923-001").
     */
    private String referenceNumber;

    /**
     * ID of the merchant who initiated transaction.
     * Used by compliance officers to filter by merchant.
     */
    private UUID merchantId;

    /**
     * Email of the merchant.
     * Useful for identification in compliance dashboard.
     */
    private String merchantEmail;

    /**
     * Transaction amount.
     */
    private BigDecimal amount;

    /**
     * Currency code (3-letter ISO, e.g., "USD").
     */
    private String currency;

    /**
     * Current transaction status.
     * Examples: CREATED, PAYMENT_RECEIVED, OCR_PROCESSING, POLICY_EVALUATION, APPROVED, COMPLIANCE_HOLD
     */
    private String status;

    /**
     * Beneficiary name (recipient of funds).
     */
    private String beneficiaryName;

    /**
     * Purpose code for the transaction (e.g., "CONSULTING", "SUPPLY").
     */
    private String purposeCode;

    /**
     * When transaction was created.
     */
    private Instant createdAt;

    /**
     * When transaction was last updated.
     * Helps identify stale transactions in hold state.
     */
    private Instant updatedAt;

    /**
     * Number of documents uploaded for this transaction.
     * Used to show evidence collection progress.
     */
    private long documentCount;

    /**
     * Number of audit events for this transaction.
     * Used to show workflow complexity and history depth.
     */
    private long auditEventCount;

    /**
     * Number of manual review notes added by compliance officers.
     * Used to show investigation depth.
     */
    private long noteCount;

    /**
     * Type of the last audit event (most recent).
     * Used to show current workflow stage without fetching full timeline.
     * Examples: TRANSACTION_CREATED, PAYMENT_RECEIVED, OCR_COMPLETED, POLICY_APPROVED
     */
    private String lastEventType;

    /**
     * Factory method to convert Transaction entity to search response.
     * Note: This version doesn't include counts - those are fetched separately
     * via repositories to avoid N+1 queries.
     *
     * @param transaction the transaction entity
     * @return the search response
     */
    public static TransactionSearchResponse from(Transaction transaction) {
        return TransactionSearchResponse.builder()
            .id(transaction.getId())
            .referenceNumber(transaction.getReferenceNumber())
            .merchantId(transaction.getMerchant().getId())
            .merchantEmail(transaction.getMerchant().getEmail())
            .amount(transaction.getAmount())
            .currency(transaction.getCurrency())
            .status(transaction.getStatus().name())
            .beneficiaryName(transaction.getBeneficiaryName())
            .purposeCode(transaction.getPurposeCode())
            .createdAt(transaction.getCreatedAt())
            .updatedAt(transaction.getUpdatedAt())
            .build();
    }
}
