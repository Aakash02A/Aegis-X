package com.aegisx.review.repository;

import com.aegisx.auth.entity.User;
import com.aegisx.review.entity.ComplianceReview;
import com.aegisx.review.entity.ReviewDecision;
import com.aegisx.transaction.entity.Transaction;
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
 * Repository for Compliance Review entities.
 * Provides data access methods for compliance officer review tracking.
 */
@Repository
public interface ComplianceReviewRepository extends JpaRepository<ComplianceReview, UUID> {

    /**
     * Finds all reviews for a specific transaction.
     */
    List<ComplianceReview> findByTransaction(Transaction transaction);

    /**
     * Finds reviews by transaction ID.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.transaction.id = :transactionId " +
           "ORDER BY cr.reviewedAt DESC")
    List<ComplianceReview> findByTransactionId(@Param("transactionId") UUID transactionId);

    @Query(value = "SELECT cr FROM ComplianceReview cr JOIN FETCH cr.transaction JOIN FETCH cr.reviewer " +
            "WHERE cr.transaction.id = :transactionId",
            countQuery = "SELECT COUNT(cr) FROM ComplianceReview cr WHERE cr.transaction.id = :transactionId")
    Page<ComplianceReview> findPagedHistoryByTransactionId(@Param("transactionId") UUID transactionId,
                                                           Pageable pageable);

    /**
     * Finds the latest review for a transaction.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.transaction.id = :transactionId " +
           "ORDER BY cr.reviewedAt DESC LIMIT 1")
    Optional<ComplianceReview> findLatestByTransactionId(@Param("transactionId") UUID transactionId);

    /**
     * Finds all reviews by a specific reviewer.
     */
    List<ComplianceReview> findByReviewer(User reviewer);

    /**
     * Finds reviews by reviewer ID with pagination.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.reviewer.id = :reviewerId " +
           "ORDER BY cr.reviewedAt DESC")
    Page<ComplianceReview> findByReviewerId(@Param("reviewerId") UUID reviewerId, Pageable pageable);

    /**
     * Finds reviews by decision type.
     */
    List<ComplianceReview> findByDecision(ReviewDecision decision);

    /**
     * Finds reviews by decision type and date range.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.decision = :decision " +
           "AND cr.reviewedAt BETWEEN :startDate AND :endDate")
    List<ComplianceReview> findByDecisionAndDateRange(@Param("decision") ReviewDecision decision,
                                                      @Param("startDate") LocalDateTime startDate,
                                                      @Param("endDate") LocalDateTime endDate);

    /**
     * Finds reviews by reviewer and decision.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.reviewer.id = :reviewerId " +
           "AND cr.decision = :decision ORDER BY cr.reviewedAt DESC")
    List<ComplianceReview> findByReviewerAndDecision(@Param("reviewerId") UUID reviewerId,
                                                     @Param("decision") ReviewDecision decision);

    /**
     * Finds reviews by reviewer and date range.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.reviewer.id = :reviewerId " +
           "AND cr.reviewedAt BETWEEN :startDate AND :endDate ORDER BY cr.reviewedAt DESC")
    List<ComplianceReview> findByReviewerAndDateRange(@Param("reviewerId") UUID reviewerId,
                                                      @Param("startDate") LocalDateTime startDate,
                                                      @Param("endDate") LocalDateTime endDate);

    long countByReviewer_IdAndReviewedAtBetween(UUID reviewerId, LocalDateTime startDate, LocalDateTime endDate);

    long countByReviewer_IdAndDecisionAndReviewedAtBetween(UUID reviewerId, ReviewDecision decision,
                                                           LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Counts reviews by reviewer.
     */
    long countByReviewer(User reviewer);

    /**
     * Counts reviews by decision type.
     */
    long countByDecision(ReviewDecision decision);

    /**
     * Finds recent reviews within a time period.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.reviewedAt >= :since " +
           "ORDER BY cr.reviewedAt DESC")
    List<ComplianceReview> findRecentReviews(@Param("since") LocalDateTime since);

    /**
     * Finds reviewer performance statistics.
     */
    @Query("SELECT cr.reviewer.id, cr.reviewer.fullName, cr.decision, COUNT(cr) " +
           "FROM ComplianceReview cr GROUP BY cr.reviewer.id, cr.reviewer.fullName, cr.decision " +
           "ORDER BY cr.reviewer.fullName, cr.decision")
    List<Object[]> findReviewerStatistics();

    /**
     * Finds approval rate by reviewer.
     */
    @Query("SELECT cr.reviewer.id, cr.reviewer.fullName, " +
           "COUNT(CASE WHEN cr.decision = 'APPROVED' THEN 1 END) as approvals, " +
           "COUNT(cr) as total FROM ComplianceReview cr " +
           "GROUP BY cr.reviewer.id, cr.reviewer.fullName " +
           "ORDER BY cr.reviewer.fullName")
    List<Object[]> findApprovalRateByReviewer();

    /**
     * Checks if a transaction has been reviewed.
     */
    @Query("SELECT CASE WHEN COUNT(cr) > 0 THEN true ELSE false END " +
           "FROM ComplianceReview cr WHERE cr.transaction.id = :transactionId")
    boolean hasBeenReviewed(@Param("transactionId") UUID transactionId);

    /**
     * Finds reviews that resulted in approvals.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.decision = 'APPROVED' " +
           "ORDER BY cr.reviewedAt DESC")
    List<ComplianceReview> findApprovedReviews();

    /**
     * Finds reviews that resulted in rejections.
     */
    @Query("SELECT cr FROM ComplianceReview cr WHERE cr.decision = 'REJECTED' " +
           "ORDER BY cr.reviewedAt DESC")
    List<ComplianceReview> findRejectedReviews();
}
