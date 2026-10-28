package com.aegisx.policy.repository;

import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.entity.ViolationSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Policy Violation entities.
 * Provides data access methods for policy rule failure tracking.
 */
@Repository
public interface PolicyViolationRepository extends JpaRepository<PolicyViolation, UUID> {

    /**
     * Finds all violations for a specific policy evaluation.
     */
    List<PolicyViolation> findByPolicyEvaluation(PolicyEvaluation policyEvaluation);

    /**
     * Finds all violations by policy evaluation ID.
     */
    @Query("SELECT pv FROM PolicyViolation pv WHERE pv.policyEvaluation.id = :evaluationId")
    List<PolicyViolation> findByPolicyEvaluationId(@Param("evaluationId") UUID evaluationId);

    /**
     * Finds all violations for a specific transaction.
     */
    @Query("SELECT pv FROM PolicyViolation pv WHERE pv.policyEvaluation.transaction.id = :transactionId")
    List<PolicyViolation> findByTransactionId(@Param("transactionId") UUID transactionId);

    /**
     * Finds violations by rule ID.
     */
    List<PolicyViolation> findByRuleId(String ruleId);

    /**
     * Finds violations by severity level.
     */
    List<PolicyViolation> findBySeverity(ViolationSeverity severity);

    /**
     * Finds critical violations for a transaction.
     */
    @Query("SELECT pv FROM PolicyViolation pv WHERE pv.policyEvaluation.transaction.id = :transactionId " +
           "AND pv.severity = 'CRITICAL'")
    List<PolicyViolation> findCriticalViolationsByTransactionId(@Param("transactionId") UUID transactionId);

    /**
     * Finds violations by severity and date range.
     */
    @Query("SELECT pv FROM PolicyViolation pv WHERE " +
           "(:severity IS NULL OR pv.severity = :severity) " +
           "AND pv.createdAt BETWEEN :startDate AND :endDate " +
           "ORDER BY pv.createdAt DESC")
    List<PolicyViolation> findBySeverityAndDateRange(@Param("severity") ViolationSeverity severity,
                                                     @Param("startDate") Instant startDate,
                                                     @Param("endDate") Instant endDate);

    /**
     * Finds violations by severity with pagination.
     */
    Page<PolicyViolation> findBySeverity(ViolationSeverity severity, Pageable pageable);

    /**
     * Counts violations by rule ID.
     */
    long countByRuleId(String ruleId);

    /**
     * Counts violations by severity.
     */
    long countBySeverity(ViolationSeverity severity);

    /**
     * Finds most frequent rule violations.
     */
    @Query("SELECT pv.ruleId, COUNT(pv) as violationCount FROM PolicyViolation pv " +
           "GROUP BY pv.ruleId ORDER BY violationCount DESC")
    List<Object[]> findMostFrequentViolations();

    /**
     * Finds violations requiring immediate attention (Critical and High severity).
     */
    @Query("SELECT pv FROM PolicyViolation pv WHERE pv.severity IN ('CRITICAL', 'HIGH') " +
           "ORDER BY pv.severity DESC, pv.createdAt DESC")
    List<PolicyViolation> findViolationsRequiringAttention();

    /**
     * Finds latest violations for a specific rule across all evaluations.
     */
    @Query("SELECT pv FROM PolicyViolation pv WHERE pv.ruleId = :ruleId " +
           "ORDER BY pv.createdAt DESC")
    List<PolicyViolation> findLatestViolationsByRuleId(@Param("ruleId") String ruleId);

    /**
     * Checks if a transaction has any critical violations.
     */
    @Query("SELECT CASE WHEN COUNT(pv) > 0 THEN true ELSE false END " +
           "FROM PolicyViolation pv WHERE pv.policyEvaluation.transaction.id = :transactionId " +
           "AND pv.severity = 'CRITICAL'")
    boolean hasCriticalViolations(@Param("transactionId") UUID transactionId);

    /**
     * Counts distinct transactions in a status that carry a violation of the given severity.
     *
     * <p>Used by the queue summary, which needs the number of held transactions with a critical
     * finding without loading the violations (or the queue) into memory. {@code COUNT(DISTINCT)} is
     * required because one transaction can have several violations of the same severity.</p>
     */
    @Query("SELECT COUNT(DISTINCT pv.policyEvaluation.transaction.id) FROM PolicyViolation pv " +
           "WHERE pv.policyEvaluation.transaction.status = :status AND pv.severity = :severity")
    long countTransactionsWithSeverityInStatus(
            @Param("status") com.aegisx.transaction.entity.TransactionStatus status,
            @Param("severity") ViolationSeverity severity);
}
