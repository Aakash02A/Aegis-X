package com.aegisx.review.repository;

import com.aegisx.review.entity.ManualReviewNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for accessing manual review notes.
 * All queries are read-only (SELECT only, no INSERT/UPDATE/DELETE at repository level).
 * Review notes are append-only and immutable.
 */
@Repository
public interface ManualReviewNoteRepository extends JpaRepository<ManualReviewNote, UUID> {

    /**
     * Get all notes for a transaction in chronological order (oldest first).
     * Used to build the complete investigation history for a transaction.
     *
     * @param transactionId the transaction ID
     * @param pageable pagination settings
     * @return notes for this transaction, ordered by created_at ASC
     */
    Page<ManualReviewNote> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId, Pageable pageable);

    /**
     * Get all notes for a transaction in chronological order (unpaged).
     * Useful for building complete investigation history without pagination.
     *
     * @param transactionId the transaction ID
     * @return all notes for this transaction, ordered by created_at ASC
     */
    List<ManualReviewNote> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    /**
     * Get all notes written by a specific user in reverse chronological order.
     * Used for user activity tracking and compliance officer dashboards.
     *
     * @param userId the user ID
     * @param pageable pagination settings
     * @return notes written by this user, ordered by created_at DESC
     */
    Page<ManualReviewNote> findByAuthorIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Get all notes for a transaction in a specific time range.
     * Used for compliance reporting and audit trail reconstruction.
     *
     * @param transactionId the transaction ID
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @param pageable pagination settings
     * @return matching notes, ordered by created_at DESC
     */
    Page<ManualReviewNote> findByTransactionIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        UUID transactionId,
        Instant startTime,
        Instant endTime,
        Pageable pageable
    );

    /**
     * Get all notes written by a user in a specific time range.
     * Used for compliance officer activity reports.
     *
     * @param userId the user ID
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @param pageable pagination settings
     * @return matching notes, ordered by created_at DESC
     */
    Page<ManualReviewNote> findByAuthorIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        UUID userId,
        Instant startTime,
        Instant endTime,
        Pageable pageable
    );

    /**
     * Count notes for a transaction.
     * Used for transaction summaries and progress tracking.
     *
     * @param transactionId the transaction ID
     * @return count of notes for this transaction
     */
    long countByTransactionId(UUID transactionId);

    /**
     * Count notes written by a user.
     * Used for compliance officer performance metrics.
     *
     * @param userId the user ID
     * @return count of notes written by this user
     */
    long countByAuthorId(UUID userId);
}
