package com.aegisx.audit.repository;

import com.aegisx.audit.entity.AuditEvent;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for accessing audit events.
 * All queries are read-only (SELECT only, no INSERT/UPDATE/DELETE at repository level).
 * Audit events are append-only and immutable.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Get all audit events for a transaction in chronological order.
     * Used to build the complete audit timeline for a single transaction.
     *
     * @param transactionId the transaction ID
     * @param pageable pagination settings
     * @return audit events for this transaction, ordered by created_at ASC
     */
    Page<AuditEvent> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId, Pageable pageable);

    /**
     * Get all audit events for a transaction in chronological order (unpaged).
     * Useful for building complete timeline without pagination.
     *
     * @param transactionId the transaction ID
     * @return all audit events for this transaction, ordered by created_at ASC
     */
    List<AuditEvent> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    /**
     * Get all events with a specific correlation ID.
     * Used to trace a single request across all modules.
     *
     * @param correlationId the correlation ID
     * @param pageable pagination settings
     * @return all events with this correlation ID, ordered by created_at ASC
     */
    Page<AuditEvent> findByCorrelationIdOrderByCreatedAtAsc(String correlationId, Pageable pageable);

    /**
     * Get all events for a specific transaction of a given type.
     * Used to filter timeline by event type (e.g., all OCR events).
     *
     * @param transactionId the transaction ID
     * @param eventType the event type to filter by
     * @param pageable pagination settings
     * @return matching audit events, ordered by created_at ASC
     */
    Page<AuditEvent> findByTransactionIdAndEventTypeOrderByCreatedAtAsc(
        UUID transactionId,
        AuditEventType eventType,
        Pageable pageable
    );

    /**
     * Get all events from a specific module in a time range.
     * Used for module-based reporting and monitoring.
     *
     * @param module the audit module
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @param pageable pagination settings
     * @return matching audit events, ordered by created_at DESC
     */
    Page<AuditEvent> findByModuleAndCreatedAtBetweenOrderByCreatedAtDesc(
        AuditModule module,
        Instant startTime,
        Instant endTime,
        Pageable pageable
    );

    /**
     * Get all events of a specific type in a time range.
     * Used for event-based reporting (e.g., all approved transactions).
     *
     * @param eventType the event type to filter by
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @param pageable pagination settings
     * @return matching audit events, ordered by created_at DESC
     */
    Page<AuditEvent> findByEventTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
        AuditEventType eventType,
        Instant startTime,
        Instant endTime,
        Pageable pageable
    );

    /**
     * Get all events initiated by a specific actor in a time range.
     * Used for user action auditing (e.g., all uploads by user X).
     *
     * @param actorId the actor's UUID
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @param pageable pagination settings
     * @return matching audit events, ordered by created_at DESC
     */
    Page<AuditEvent> findByActorIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        UUID actorId,
        Instant startTime,
        Instant endTime,
        Pageable pageable
    );

    /**
     * Count events of a specific type in a time range.
     * Used for metrics and monitoring dashboards.
     *
     * @param eventType the event type
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @return count of matching events
     */
    long countByEventTypeAndCreatedAtBetween(AuditEventType eventType, Instant startTime, Instant endTime);

    /**
     * Count events from a specific module in a time range.
     * Used for module-level metrics.
     *
     * @param module the audit module
     * @param startTime start of time range (inclusive)
     * @param endTime end of time range (inclusive)
     * @return count of matching events
     */
    long countByModuleAndCreatedAtBetween(AuditModule module, Instant startTime, Instant endTime);

    /**
     * Find all POLICY_APPROVED or POLICY_HOLD events for a transaction.
     * Used to review policy engine decisions for a transaction.
     *
     * @param transactionId the transaction ID
     * @param pageable pagination settings
     * @return policy decision events, ordered by created_at ASC
     */
    @Query(
        "SELECT ae FROM AuditEvent ae " +
        "WHERE ae.transaction.id = :transactionId " +
        "AND ae.eventType IN ('POLICY_APPROVED', 'POLICY_HOLD') " +
        "ORDER BY ae.createdAt ASC"
    )
    Page<AuditEvent> findPolicyDecisionsForTransaction(@Param("transactionId") UUID transactionId, Pageable pageable);

    /**
     * Find all OCR_COMPLETED or OCR_FAILED events for a transaction.
     * Used to review document extraction history.
     *
     * @param transactionId the transaction ID
     * @param pageable pagination settings
     * @return OCR events, ordered by created_at ASC
     */
    @Query(
        "SELECT ae FROM AuditEvent ae " +
        "WHERE ae.transaction.id = :transactionId " +
        "AND ae.eventType IN ('OCR_COMPLETED', 'OCR_FAILED') " +
        "ORDER BY ae.createdAt ASC"
    )
    Page<AuditEvent> findOCREventsForTransaction(@Param("transactionId") UUID transactionId, Pageable pageable);

    /**
     * Find all AI analysis events for a transaction.
     * Used to review AI regulatory reasoning.
     *
     * @param transactionId the transaction ID
     * @param pageable pagination settings
     * @return AI analysis events, ordered by created_at ASC
     */
    @Query(
        "SELECT ae FROM AuditEvent ae " +
        "WHERE ae.transaction.id = :transactionId " +
        "AND ae.module = 'AI' " +
        "ORDER BY ae.createdAt ASC"
    )
    Page<AuditEvent> findAIEventsForTransaction(@Param("transactionId") UUID transactionId, Pageable pageable);
}
