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

    Page<Transaction> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);

    Page<Transaction> findByStatusIn(Collection<TransactionStatus> statuses, Pageable pageable);

    Page<Transaction> findByAmountGreaterThanEqual(BigDecimal amount, Pageable pageable);

    List<Transaction> findByStatusOrderByCreatedAtDesc(TransactionStatus status);

    Optional<Transaction> findByIdAndMerchantId(UUID id, UUID merchantId);

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
}
