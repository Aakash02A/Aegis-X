package com.aegisx.ai.service;

import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.common.exception.AIAnalysisException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * AIProcessingCoordinator orchestrates end-to-end AI analysis workflow.
 * 
 * Purpose:
 * - Coordinate between service, parser, normalizer, repository
 * - Provide high-level API for triggering analysis
 * - Retrieve latest analysis for a transaction
 * - Ensure consistency and auditability throughout flow
 * 
 * Design:
 * - Single entry point for analysis initiation
 * - Encapsulates retry logic and error handling (future extension)
 * - Manages transaction state transitions
 * - Guarantees immutability: once persisted, analysis never changes
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIProcessingCoordinator {
    
    private final AIAnalysisService aiAnalysisService;
    private final AIAnalysisRepository aiAnalysisRepository;
    
    /**
     * Trigger AI analysis for a transaction.
     * 
     * @param input Analysis request with transaction and OCR data
     * @return Persisted analysis with advisory findings
     * @throws AIAnalysisException if analysis fails
     */
    @Transactional
    public AIAnalysis triggerAnalysis(AIAnalysisService.AIAnalysisInput input) {
        log.info("Triggering AI analysis for transaction: {}", input.getTransactionId());
        
        try {
            // Validate input
            if (input.getTransactionId() == null) {
                throw new AIAnalysisException("Transaction ID cannot be null");
            }
            if (input.getExtractionId() == null) {
                throw new AIAnalysisException("Extraction ID cannot be null");
            }
            
            // Perform analysis
            AIAnalysis analysis = aiAnalysisService.performAnalysis(input);
            
            log.info("AI analysis completed: id={}, transactionId={}, confidence={}", 
                analysis.getId(), analysis.getTransactionId(), analysis.getConfidenceScore());
            
            return analysis;
            
        } catch (AIAnalysisException e) {
            log.warn("AI advisory analysis failed for transaction {}", input.getTransactionId());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected AI advisory error for transaction {} ({})",
                input.getTransactionId(), e.getClass().getSimpleName());
            throw new AIAnalysisException("AI advisory analysis failed", e);
        }
    }
    
    /**
     * Retrieve the latest AI analysis for a transaction.
     * 
     * @param transactionId Transaction to query
     * @return Latest analysis if available, empty if none exists
     */
    @Transactional(readOnly = true)
    public Optional<AIAnalysis> getLatestAnalysis(UUID transactionId) {
        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID cannot be null");
        }
        
        return aiAnalysisRepository.findLatestByTransactionId(transactionId);
    }
    
    /**
     * Check if analysis exists for a transaction.
     */
    @Transactional(readOnly = true)
    public boolean analysisExists(UUID transactionId) {
        return getLatestAnalysis(transactionId).isPresent();
    }
}
