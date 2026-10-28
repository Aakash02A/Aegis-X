package com.aegisx.settlement.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.transaction.entity.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Settlement entity representing the final settlement of a transaction.
 * Implements settlement tracking according to TAS Section 9.2.
 */
@Entity
@Table(name = "settlements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settlement extends BaseEntity {

    // The identifier is inherited from BaseEntity, which assigns it in @PrePersist. Declaring a
    // second @Id field here shadowed that one and left this entity's id null on persist.

    /**
     * Reference to the transaction being settled.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /**
     * Unique settlement reference number.
     */
    @Column(name = "settlement_reference", nullable = false, unique = true, length = 50)
    private String settlementReference;

    /**
     * Payment processor handling the settlement.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_processor", nullable = false)
    private PaymentProcessor paymentProcessor;

    /**
     * External processor transaction ID.
     */
    @Column(name = "processor_transaction_id", length = 100)
    private String processorTransactionId;

    /**
     * Current settlement status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SettlementStatus status;

    /**
     * Settlement amount (may differ from transaction amount due to fees).
     */
    @Column(name = "settlement_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal settlementAmount;

    /**
     * Settlement currency.
     */
    @Column(name = "settlement_currency", nullable = false, length = 3)
    private String settlementCurrency;

    /**
     * Exchange rate applied (if currency conversion occurred).
     */
    @Column(name = "exchange_rate", precision = 10, scale = 6)
    private BigDecimal exchangeRate;

    /**
     * Processing fees charged by payment processor.
     */
    @Column(name = "processing_fees", precision = 10, scale = 2)
    private BigDecimal processingFees;

    /**
     * When settlement was initiated.
     */
    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    /**
     * When settlement was submitted to processor.
     */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /**
     * When settlement was completed.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Expected completion time provided by processor.
     */
    @Column(name = "expected_completion_at")
    private LocalDateTime expectedCompletionAt;

    /**
     * Settlement failure reason (if failed).
     */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * Number of retry attempts.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * Maximum number of retries allowed.
     */
    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    /**
     * Next retry attempt time (if applicable).
     */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /**
     * Processor-specific metadata and response data.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "processor_metadata")
    private JsonNode processorMetadata;

    /**
     * Settlement reconciliation data.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reconciliation_data")
    private JsonNode reconciliationData;

    /**
     * When settlement was last reconciled.
     */
    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    /**
     * User who initiated the settlement.
     */
    @Column(name = "initiated_by", length = 100)
    private String initiatedBy;

    /**
     * Creates a new settlement for a transaction.
     */
    public static Settlement createSettlement(Transaction transaction, 
                                            PaymentProcessor processor, 
                                            BigDecimal amount, 
                                            String currency,
                                            String initiatedBy) {
        String reference = generateSettlementReference(processor);
        
        return Settlement.builder()
                .transaction(transaction)
                .settlementReference(reference)
                .paymentProcessor(processor)
                .status(SettlementStatus.INITIATED)
                .settlementAmount(amount)
                .settlementCurrency(currency)
                .initiatedAt(LocalDateTime.now())
                .initiatedBy(initiatedBy)
                .retryCount(0)
                .maxRetries(3)
                .build();
    }

    /**
     * Generates a unique settlement reference.
     */
    private static String generateSettlementReference(PaymentProcessor processor) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return String.format("STL-%s-%s-%s", processor.getCode(), timestamp, random);
    }

    /**
     * Updates settlement status with timestamp tracking.
     */
    public void updateStatus(SettlementStatus newStatus, String reason) {
        this.status = newStatus;
        
        switch (newStatus) {
            case SUBMITTED -> this.submittedAt = LocalDateTime.now();
            case COMPLETED -> this.completedAt = LocalDateTime.now();
            case FAILED, REJECTED, CANCELLED -> {
                this.failureReason = reason;
                if (newStatus == SettlementStatus.FAILED && canRetry()) {
                    scheduleNextRetry();
                }
            }
            case RECONCILED -> this.reconciledAt = LocalDateTime.now();
        }
        
        this.setUpdatedAt(LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC));
    }

    /**
     * Sets processor transaction ID and metadata.
     */
    public void setProcessorDetails(String transactionId, JsonNode metadata) {
        this.processorTransactionId = transactionId;
        this.processorMetadata = metadata;
        this.setUpdatedAt(LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC));
    }

    /**
     * Sets expected completion time.
     */
    public void setExpectedCompletion(LocalDateTime expectedAt) {
        this.expectedCompletionAt = expectedAt;
        this.setUpdatedAt(LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC));
    }

    /**
     * Increments retry count and schedules next retry.
     */
    public void incrementRetry() {
        this.retryCount++;
        scheduleNextRetry();
        this.setUpdatedAt(LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC));
    }

    /**
     * Checks if settlement can be retried.
     */
    public boolean canRetry() {
        return retryCount < maxRetries && status.allowsRetry();
    }

    /**
     * Schedules next retry attempt with exponential backoff.
     */
    private void scheduleNextRetry() {
        if (canRetry()) {
            // Exponential backoff: 15 minutes, 1 hour, 4 hours
            long delayMinutes = (long) Math.pow(4, retryCount) * 15;
            this.nextRetryAt = LocalDateTime.now().plusMinutes(delayMinutes);
        }
    }

    /**
     * Returns true if settlement is overdue based on expected completion.
     */
    public boolean isOverdue() {
        return expectedCompletionAt != null && 
               LocalDateTime.now().isAfter(expectedCompletionAt) && 
               status.isInProgress();
    }

    /**
     * Calculates settlement duration in hours.
     */
    public Long getSettlementDurationHours() {
        if (initiatedAt == null) return null;
        
        LocalDateTime endTime = completedAt != null ? 
                completedAt : LocalDateTime.now();
        
        return java.time.Duration.between(initiatedAt, endTime).toHours();
    }

    /**
     * Returns true if settlement is in a final state.
     */
    public boolean isFinal() {
        return status.isFinal();
    }

    /**
     * Returns true if settlement completed successfully.
     */
    public boolean isSuccessful() {
        return status.isSuccess();
    }

    /**
     * Returns true if settlement failed.
     */
    public boolean isFailed() {
        return status.isError();
    }

    /**
     * Gets net settlement amount after fees.
     */
    public BigDecimal getNetAmount() {
        if (processingFees == null) {
            return settlementAmount;
        }
        return settlementAmount.subtract(processingFees);
    }

    /**
     * Sets reconciliation data.
     */
    public void setReconciliationData(JsonNode data) {
        this.reconciliationData = data;
        this.setUpdatedAt(LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC));
    }
}
