package com.aegisx.settlement.repository;

import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Settlement entity operations.
 * Provides data access methods for settlement management.
 */
@Repository
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    /**
     * Finds settlement by settlement reference number.
     */
    Optional<Settlement> findBySettlementReference(String settlementReference);

    /** Serializes payout processing so concurrent callers cannot submit one settlement twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.id = :settlementId")
    Optional<Settlement> findByIdForUpdate(@Param("settlementId") UUID settlementId);

    @Query("SELECT s FROM Settlement s WHERE s.id = :settlementId AND s.transaction.merchant.id = :merchantId")
    Optional<Settlement> findByIdAndMerchantId(@Param("settlementId") UUID settlementId,
                                               @Param("merchantId") UUID merchantId);

    @Query("SELECT s FROM Settlement s WHERE s.settlementReference = :reference "
            + "AND s.transaction.merchant.id = :merchantId")
    Optional<Settlement> findByReferenceAndMerchantId(@Param("reference") String reference,
                                                       @Param("merchantId") UUID merchantId);

    /**
     * Finds settlement by processor transaction ID.
     */
    Optional<Settlement> findByProcessorTransactionId(String processorTransactionId);

    /**
     * Finds settlements for a specific transaction.
     */
    List<Settlement> findByTransactionId(UUID transactionId);

    /**
     * Finds settlements by status.
     */
    List<Settlement> findByStatus(SettlementStatus status);

    /**
     * Finds settlements by status with pagination.
     */
    Page<Settlement> findByStatus(SettlementStatus status, Pageable pageable);

    /**
     * Finds settlements by payment processor.
     */
    List<Settlement> findByPaymentProcessor(PaymentProcessor processor);

    /**
     * Finds settlements requiring retry.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status IN ('FAILED', 'REJECTED') " +
           "AND s.retryCount < s.maxRetries " +
           "AND (s.nextRetryAt IS NULL OR s.nextRetryAt <= :now)")
    List<Settlement> findSettlementsRequiringRetry(@Param("now") LocalDateTime now);

    /**
     * Finds overdue settlements.
     */
    @Query("SELECT s FROM Settlement s WHERE s.expectedCompletionAt < :now " +
           "AND s.status NOT IN ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'RECONCILED')")
    List<Settlement> findOverdueSettlements(@Param("now") LocalDateTime now);

    /**
     * Finds settlements pending reconciliation.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status = 'COMPLETED' " +
           "AND s.reconciledAt IS NULL")
    List<Settlement> findSettlementsPendingReconciliation();

    /**
     * Finds settlements by status and date range.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status = :status " +
           "AND s.initiatedAt BETWEEN :startDate AND :endDate")
    List<Settlement> findByStatusAndDateRange(@Param("status") SettlementStatus status,
                                            @Param("startDate") LocalDateTime startDate,
                                            @Param("endDate") LocalDateTime endDate);

    /**
     * Finds settlements by multiple statuses.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status IN :statuses")
    List<Settlement> findByStatusIn(@Param("statuses") List<SettlementStatus> statuses);

    /**
     * Gets settlement statistics by processor.
     */
    @Query("SELECT s.paymentProcessor, COUNT(s), AVG(s.settlementAmount) " +
           "FROM Settlement s WHERE s.initiatedAt BETWEEN :startDate AND :endDate " +
           "GROUP BY s.paymentProcessor")
    List<Object[]> getSettlementStatsByProcessor(@Param("startDate") LocalDateTime startDate,
                                                @Param("endDate") LocalDateTime endDate);

    /**
     * Gets settlement success rate by processor.
     */
    @Query("SELECT s.paymentProcessor, " +
           "COUNT(CASE WHEN s.status = 'COMPLETED' THEN 1 END) as successful, " +
           "COUNT(s) as total " +
           "FROM Settlement s WHERE s.initiatedAt BETWEEN :startDate AND :endDate " +
           "GROUP BY s.paymentProcessor")
    List<Object[]> getSettlementSuccessRateByProcessor(@Param("startDate") LocalDateTime startDate,
                                                      @Param("endDate") LocalDateTime endDate);

    /**
     * Gets average settlement duration by processor.
     */
    @Query(value = "SELECT payment_processor, " +
           "AVG(EXTRACT(EPOCH FROM (completed_at - initiated_at))/3600) AS avg_hours " +
           "FROM settlements WHERE status = 'COMPLETED' " +
           "AND initiated_at BETWEEN :startDate AND :endDate " +
           "GROUP BY payment_processor", nativeQuery = true)
    List<Object[]> getAverageSettlementDuration(@Param("startDate") LocalDateTime startDate,
                                               @Param("endDate") LocalDateTime endDate);

    /**
     * Finds settlements requiring manual intervention.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status IN ('MANUAL_REVIEW', 'RECONCILIATION_FAILED') " +
           "ORDER BY s.initiatedAt ASC")
    List<Settlement> findSettlementsRequiringManualIntervention();

    /**
     * Finds failed settlements with high retry count.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status IN ('FAILED', 'REJECTED') " +
           "AND s.retryCount >= s.maxRetries")
    List<Settlement> findFailedSettlementsExhaustedRetries();

    /**
     * Gets settlement volume and count by date.
     */
    @Query("SELECT DATE(s.initiatedAt) as settlementDate, " +
           "COUNT(s) as settlementCount, " +
           "SUM(s.settlementAmount) as totalAmount " +
           "FROM Settlement s WHERE s.initiatedAt BETWEEN :startDate AND :endDate " +
           "GROUP BY DATE(s.initiatedAt) " +
           "ORDER BY DATE(s.initiatedAt)")
    List<Object[]> getSettlementVolumeByDate(@Param("startDate") LocalDateTime startDate,
                                            @Param("endDate") LocalDateTime endDate);

    /**
     * Finds settlements with reconciliation discrepancies.
     */
    @Query("SELECT s FROM Settlement s WHERE s.status = 'RECONCILIATION_FAILED' " +
           "ORDER BY s.reconciledAt DESC")
    List<Settlement> findSettlementsWithReconciliationDiscrepancies();

    /**
     * Gets processor performance metrics.
     */
    @Query("SELECT s.paymentProcessor, s.status, COUNT(s) " +
           "FROM Settlement s WHERE s.initiatedAt BETWEEN :startDate AND :endDate " +
           "GROUP BY s.paymentProcessor, s.status " +
           "ORDER BY s.paymentProcessor, s.status")
    List<Object[]> getProcessorPerformanceMetrics(@Param("startDate") LocalDateTime startDate,
                                                 @Param("endDate") LocalDateTime endDate);

    /**
     * Checks if transaction has existing settlement.
     */
    boolean existsByTransactionId(UUID transactionId);

    /**
     * Counts settlements by status.
     */
    @Query("SELECT s.status, COUNT(s) FROM Settlement s GROUP BY s.status")
    List<Object[]> countSettlementsByStatus();

    /**
     * Finds recent settlements for monitoring.
     */
    @Query("SELECT s FROM Settlement s WHERE s.initiatedAt >= :since " +
           "ORDER BY s.initiatedAt DESC")
    List<Settlement> findRecentSettlements(@Param("since") LocalDateTime since,
                                         Pageable pageable);
}
