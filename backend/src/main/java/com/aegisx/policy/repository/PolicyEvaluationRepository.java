package com.aegisx.policy.repository;

import com.aegisx.policy.entity.PolicyEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PolicyEvaluationRepository provides data access for immutable policy evaluation records.
 * 
 * Design:
 * - No update or delete operations; append-only audit log
 * - Supports query by transaction, retrieves latest decision
 * - Enables compliance history and trend analysis
 */
@Repository
public interface PolicyEvaluationRepository extends JpaRepository<PolicyEvaluation, UUID> {
    
    /**
     * Find the most recent policy evaluation for a transaction.
     * Used to retrieve the final decision without needing explicit version tracking.
     */
    @Query(value = "SELECT * FROM policy_evaluations WHERE transaction_id = :transactionId ORDER BY created_at DESC LIMIT 1", nativeQuery = true)
    Optional<PolicyEvaluation> findLatestByTransactionId(@Param("transactionId") UUID transactionId);
    
    /**
     * Find all evaluations for a transaction, ordered by creation time descending.
     * Enables audit history review and decision trend analysis.
     */
    List<PolicyEvaluation> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
    
    /**
     * Find all evaluations for a given final decision.
     * Useful for compliance reporting and metrics.
     */
    List<PolicyEvaluation> findByFinalDecisionOrderByCreatedAtDesc(String finalDecision);
}
