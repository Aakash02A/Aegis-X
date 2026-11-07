package com.aegisx.ai.controller;

import com.aegisx.ai.dto.AIAnalysisResponse;
import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.ai.service.AIAnalysisService;
import com.aegisx.ai.service.AIProcessingCoordinator;
import com.aegisx.common.exception.AIAnalysisException;
import com.aegisx.common.exception.TransactionNotFoundException;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AIAnalysisController provides REST endpoints for AI regulatory analysis.
 * 
 * Purpose:
 * - Trigger AI analysis on completed OCR extraction
 * - Retrieve latest AI analysis for a transaction
 * - Support merchant isolation and compliance officer auditing
 * 
 * Endpoints:
 * - POST /api/transactions/{id}/ai-analysis: Trigger analysis
 * - GET /api/transactions/{id}/ai-analysis: Retrieve latest analysis
 * 
 * Security:
 * - JWT authentication required
 * - Merchant can view only their transactions
 * - Compliance Officer can view all analyses
 * - Admin retains full access
 * 
 * Design:
 * - Never expose raw prompts or internal details
 * - Return advisory findings only
 * - Include full audit trail (model, version, hash, confidence, duration)
 * - Transaction remains AI_ANALYSIS until Phase 10 Policy Engine acts
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class AIAnalysisController {
    
    private final AIProcessingCoordinator aiProcessingCoordinator;
    private final AIAnalysisRepository aiAnalysisRepository;
    private final TransactionRepository transactionRepository;
    private final DocumentExtractionRepository documentExtractionRepository;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;
    private final TransactionService transactionService;
    
    /**
     * Trigger AI analysis for a completed OCR extraction.
     * 
     * POST /api/transactions/{id}/ai-analysis
     * 
     * @param transactionId Transaction to analyze
     * @return Analysis response with findings or error details
     */
    @PostMapping("/{id}/ai-analysis")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN')")
    public ResponseEntity<?> triggerAnalysis(@PathVariable("id") UUID transactionId, Authentication authentication) {
        try {
            log.info("Triggering AI analysis for transaction: {}", transactionId);
            
            // Retrieve transaction and verify caller authorization
            Transaction transaction = transactionService.getAuthorizedTransaction(transactionId, authentication);
            
            // Retrieve latest OCR extraction
            DocumentExtraction extraction = documentExtractionRepository.findLatestByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No OCR extraction found for transaction: " + transactionId));
            
            // Retrieve document for OCR data
            Document document = documentRepository.findByTransactionIdOrderByCreatedAtDesc(transactionId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
            
            // Build analysis input
            AIAnalysisService.AIAnalysisInput input = AIAnalysisService.AIAnalysisInput.builder()
                .transactionId(transactionId)
                .extractionId(extraction.getId())
                .purpose(transaction.getPurpose())
                .beneficiaryName(transaction.getBeneficiaryName())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .ocrJson(extraction.getExtractedData())
                .extractedText(extraction.getExtractedText())
                .build();
            
            // Trigger analysis
            AIAnalysis analysis = aiProcessingCoordinator.triggerAnalysis(input);
            if (transaction.getStatus() == TransactionStatus.AI_ANALYSIS) {
                transactionService.transitionStatus(transactionId, TransactionStatus.POLICY_EVALUATION,
                        "AI_SERVICE", "AI analysis completed");
            }
            
            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "AI_ANALYSIS");
            response.put("analysisId", analysis.getId());
            response.put("transactionId", transactionId);
            response.put("confidence", analysis.getConfidenceScore());
            response.put("processingDurationMs", analysis.getProcessingDurationMs());
            response.put("message", "AI analysis completed and persisted");
            
            log.info("AI analysis triggered successfully for transaction: {}", transactionId);
            return ResponseEntity.ok(response);
            
        } catch (TransactionNotFoundException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for AI analysis: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", e.getMessage()
            ));
        } catch (AIAnalysisException e) {
            log.error("AI analysis failed for transaction {}: {}", transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "AI Analysis Failed",
                "message", e.getMessage(),
                "transactionId", transactionId
            ));
        } catch (Exception e) {
            log.error("Unexpected error during AI analysis for transaction {}: {}", transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "An unexpected error occurred during AI analysis"
            ));
        }
    }
    
    /**
     * Retrieve the latest AI analysis for a transaction.
     * 
     * GET /api/transactions/{id}/ai-analysis
     * 
     * @param transactionId Transaction to query
     * @return Latest AI analysis with findings and audit trail
     */
    @GetMapping("/{id}/ai-analysis")
    @PreAuthorize("hasAnyRole('MERCHANT', 'COMPLIANCE_OFFICER', 'ADMIN')")
    public ResponseEntity<?> getLatestAnalysis(@PathVariable("id") UUID transactionId, Authentication authentication) {
        try {
            log.info("Retrieving latest AI analysis for transaction: {}", transactionId);
            
            // Verify transaction exists and caller is authorized
            transactionService.getAuthorizedTransaction(transactionId, authentication);
            
            // Retrieve latest analysis
            var analysis = aiProcessingCoordinator.getLatestAnalysis(transactionId);
            
            if (analysis.isEmpty()) {
                log.info("No AI analysis found for transaction: {}", transactionId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Not Found",
                    "message", "No AI analysis found for this transaction"
                ));
            }
            
            // Convert to response DTO
            AIAnalysis aiAnalysis = analysis.get();
            AIAnalysisResponse response = AIAnalysisResponse.builder()
                .id(aiAnalysis.getId())
                .transactionId(aiAnalysis.getTransactionId())
                .extractionId(aiAnalysis.getExtractionId())
                .modelName(aiAnalysis.getModelName())
                .promptVersion(aiAnalysis.getPromptVersion())
                .inputHash(aiAnalysis.getInputHash())
                .overallConfidence(aiAnalysis.getConfidenceScore())
                .processingDurationMs(aiAnalysis.getProcessingDurationMs())
                .createdAt(aiAnalysis.getCreatedAt())
                .build();
            
            // Parse analysis JSON into detailed findings
            try {
                var analysisJson = objectMapper.readTree(aiAnalysis.getAnalysisJson().toString());
                if (analysisJson.has("summary")) {
                    response.setSummary(analysisJson.get("summary").asText());
                }
                if (analysisJson.has("findings")) {
                    response.setFindings(objectMapper.convertValue(analysisJson.get("findings"), 
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, com.aegisx.ai.dto.ComplianceFinding.class)));
                }
                if (analysisJson.has("missingEvidence")) {
                    response.setMissingEvidence(objectMapper.convertValue(analysisJson.get("missingEvidence"), 
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)));
                }
                if (analysisJson.has("recommendedChecks")) {
                    response.setRecommendedChecks(objectMapper.convertValue(analysisJson.get("recommendedChecks"), 
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)));
                }
            } catch (Exception e) {
                log.warn("Failed to parse analysis JSON for transaction {}: {}", transactionId, e.getMessage());
            }
            
            log.info("Retrieved AI analysis for transaction: {}", transactionId);
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for AI analysis retrieval: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error retrieving AI analysis for transaction {}: {}", transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "An error occurred while retrieving analysis"
            ));
        }
    }
}
