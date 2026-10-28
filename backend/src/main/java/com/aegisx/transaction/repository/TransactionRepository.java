package com.aegisx.transaction.repository;

import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Collection;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Query("SELECT t FROM Transaction t WHERE t.merchant.id = :merchantId")
    Page<Transaction> findByMerchantId(@Param("merchantId") UUID merchantId, Pageable pageable);

    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);

    Page<Transaction> findByStatusIn(Collection<TransactionStatus> statuses, Pageable pageable);

    Page<Transaction> findByAmountGreaterThanEqual(BigDecimal amount, Pageable pageable);

    List<Transaction> findByStatusOrderByCreatedAtDesc(TransactionStatus status);

    @Query("SELECT t FROM Transaction t WHERE t.id = :id AND t.merchant.id = :merchantId")
    Optional<Transaction> findByIdAndMerchantId(@Param("id") UUID id, @Param("merchantId") UUID merchantId);

    Optional<Transaction> findByReferenceNumber(String referenceNumber);

    /**
     * Finds transactions by merchant ID and status.
     */
    @Query("SELECT t FROM Transaction t WHERE t.merchant.id = :merchantId AND t.status = :status")
    List<Transaction> findByMerchantIdAndStatus(@Param("merchantId") UUID merchantId, @Param("status") TransactionStatus status);

    /**
     * Counts transactions by status.
     */
    long countByStatus(TransactionStatus status);

    /**
     * Finds transactions that are in terminal states.
     */
    @Query("SELECT t FROM Transaction t WHERE t.isTerminal = true")
    List<Transaction> findTerminalTransactions();

    /**
     * Finds transactions by status with pagination, ordered by created date.
     */
    Page<Transaction> findByStatusOrderByCreatedAtDesc(TransactionStatus status, Pageable pageable);

    /**
     * Lists transactions in a status that have no compliance review yet.
     *
     * <p>The "unreviewed" condition runs in the database so that the reviewer queue does not have to
     * load and enrich every held transaction before discarding the reviewed ones in memory.</p>
     */
    @Query("SELECT t FROM Transaction t WHERE t.status = :status AND NOT EXISTS " +
            "(SELECT cr.id FROM ComplianceReview cr WHERE cr.transaction = t) " +
            "ORDER BY t.createdAt DESC")
    List<Transaction> findUnreviewedByStatus(@Param("status") TransactionStatus status);

    /**
     * Returns the oldest {@code updatedAt} for a status, used by the queue summary.
     *
     * <p>The queue treats {@code updatedAt} as the moment a transaction entered the hold, so the
     * oldest row is the one that has been waiting longest.</p>
     */
    @Query("SELECT MIN(t.updatedAt) FROM Transaction t WHERE t.status = :status")
    Optional<java.time.Instant> findOldestUpdatedAtByStatus(@Param("status") TransactionStatus status);
}
