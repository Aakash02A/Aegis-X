package com.aegisx.ai.repository;

import com.aegisx.ai.entity.AIAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AIAnalysisRepository provides data access for immutable AI analysis records.
 * 
 * Design:
 * - No update or delete operations; append-only audit log
 * - Supports query by transaction, extraction, input hash
 * - Retrieves latest analysis for a transaction
 * - Enables reproducibility checks via input hash
 */
@Repository
public interface AIAnalysisRepository extends JpaRepository<AIAnalysis, UUID> {
    
    /**
     * Find the most recent AI analysis for a transaction.
     * Used to retrieve latest findings without needing explicit version tracking.
     */
    @Query(value = "SELECT * FROM ai_analysis WHERE transaction_id = :transactionId ORDER BY created_at DESC LIMIT 1", nativeQuery = true)
    Optional<AIAnalysis> findLatestByTransactionId(@Param("transactionId") UUID transactionId);
    
    /**
     * Find all analyses for a transaction, ordered by creation time descending.
     * Enables audit history review and confidence trend analysis.
     */
    List<AIAnalysis> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
    
    /**
     * Find all analyses for an extraction.
     * Supports re-analysis scenarios where same OCR output may be analyzed multiple times.
     */
    List<AIAnalysis> findByExtractionIdOrderByCreatedAtDesc(UUID extractionId);
    
    /**
     * Check if analysis exists for given input hash.
     * Enables idempotency: prevents duplicate analyses for identical inputs.
     */
    Optional<AIAnalysis> findByInputHash(String inputHash);
    
    /**
     * Find all analyses by extraction, filtered by model name.
     * Useful for audit trails when model version changes.
     */
    List<AIAnalysis> findByExtractionIdAndModelName(UUID extractionId, String modelName);
}
