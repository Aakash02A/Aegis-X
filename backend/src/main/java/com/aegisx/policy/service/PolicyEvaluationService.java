package com.aegisx.policy.service;

import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.common.exception.MissingEvidenceException;
import com.aegisx.common.exception.PolicyEvaluationException;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.policy.dto.PolicyEvaluationResponse;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.repository.PolicyEvaluationRepository;
import com.aegisx.policy.rules.RuleContext;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PolicyEvaluationService orchestrates policy evaluation workflow.
 * 
 * Purpose:
 * - Load all required data for policy evaluation
 * - Build RuleContext
 * - Execute RuleEngine
 * - Persist policy evaluation decision
 * - Provide retrieval API for decisions
 * 
 * Design:
 * - Fail-closed: any missing evidence triggers COMPLIANCE_HOLD
 * - Immutable storage: decisions never updated
 * - State transition managed by TransactionService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyEvaluationService {
    
    private final RuleEngine ruleEngine;
    private final PolicyEvaluationRepository policyEvaluationRepository;
    private final TransactionRepository transactionRepository;
    private final DocumentExtractionRepository documentExtractionRepository;
    private final RequiredDocumentRepository requiredDocumentRepository;
    private final AIAnalysisRepository aiAnalysisRepository;
    private final ObjectMapper objectMapper;
    private final TransactionService transactionService;
    
    /**
     * Evaluate policy for a transaction.
     * 
     * @param transactionId Transaction to evaluate
     * @return Persisted PolicyEvaluation with final decision
     * @throws PolicyEvaluationException if evaluation fails
     */
    @Transactional
    public PolicyEvaluation evaluateTransaction(UUID transactionId) {
        log.info("Starting policy evaluation for transaction: {}", transactionId);
        
        try {
            // Load transaction
            Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new MissingEvidenceException("Transaction not found: " + transactionId));
            
            // Load required documents
            var requiredDocuments = requiredDocumentRepository.findByTransactionId(transactionId);
            
            // Load latest OCR extraction
            var documentExtraction = documentExtractionRepository.findLatestByTransactionId(transactionId)
                .orElseThrow(() -> new MissingEvidenceException("No OCR extraction found for transaction: " + transactionId));
            
            // Load latest AI analysis
            var aiAnalysis = aiAnalysisRepository.findLatestByTransactionId(transactionId)
                .orElseThrow(() -> new MissingEvidenceException("No AI analysis found for transaction: " + transactionId));
            
            // Build RuleContext
            RuleContext context = RuleContext.builder()
                .transaction(transaction)
                .requiredDocuments(requiredDocuments)
                .documentExtraction(documentExtraction)
                .aiAnalysis(aiAnalysis)
                .build();
            
            // Execute rules
            PolicyEngineResult result = ruleEngine.evaluate(context);
            
            // Build evaluation JSON with rule results
            var evaluationJson = objectMapper.createObjectNode();
            evaluationJson.put("decision", result.getFinalDecision());
            var rulesArray = objectMapper.createArrayNode();
            result.getRuleResults().forEach(ruleResult -> {
                var ruleNode = objectMapper.createObjectNode();
                ruleNode.put("rule", ruleResult.getRuleName());
                ruleNode.put("passed", ruleResult.getPassed());
                ruleNode.put("reason", ruleResult.getReason());
                ruleNode.put("evidence", ruleResult.getEvidence());
                rulesArray.add(ruleNode);
            });
            evaluationJson.set("rules", rulesArray);
            
            // Persist policy evaluation
            PolicyEvaluation evaluation = PolicyEvaluation.builder()
                .transaction(transaction)
                .aiAnalysis(aiAnalysis)
                .finalDecision(result.getFinalDecision())
                .overallScore(aiAnalysis.getConfidenceScore())
                .evaluationJson(evaluationJson)
                .processingDurationMs(result.getProcessingDurationMs())
                .build();
            
            PolicyEvaluation saved = policyEvaluationRepository.save(evaluation);

            TransactionStatus nextStatus = switch (result.getFinalDecision()) {
                case "APPROVED" -> TransactionStatus.APPROVED;
                case "COMPLIANCE_HOLD" -> TransactionStatus.COMPLIANCE_HOLD;
                default -> throw new PolicyEvaluationException("Unsupported policy decision: " + result.getFinalDecision());
            };
            if (transaction.getStatus() == TransactionStatus.POLICY_EVALUATION) {
                transactionService.transitionStatus(transactionId, nextStatus, "POLICY_ENGINE", "Policy evaluation decision: " + result.getFinalDecision());
            }
            
            log.info("Policy evaluation completed for transaction: {} - Decision: {}, Duration: {}ms",
                transactionId, result.getFinalDecision(), result.getProcessingDurationMs());
            
            return saved;
            
        } catch (MissingEvidenceException e) {
            log.error("Missing evidence for policy evaluation of transaction {}: {}", transactionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Policy evaluation failed for transaction {}: {}", transactionId, e.getMessage(), e);
            throw new PolicyEvaluationException("Policy evaluation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Retrieve the latest policy evaluation for a transaction.
     * 
     * @param transactionId Transaction to query
     * @return PolicyEvaluationResponse if evaluation exists
     */
    @Transactional(readOnly = true)
    public PolicyEvaluationResponse getLatestEvaluation(UUID transactionId) {
        var evaluation = policyEvaluationRepository.findLatestByTransactionId(transactionId)
            .orElse(null);
        
        if (evaluation == null) {
            return null;
        }
        
        // Extract rule results from evaluation JSON
        var rules = objectMapper.createArrayNode();
        if (evaluation.getEvaluationJson().has("rules")) {
            rules = (com.fasterxml.jackson.databind.node.ArrayNode) evaluation.getEvaluationJson().get("rules");
        }
        
        java.util.List<com.aegisx.policy.dto.RuleResult> rulesList = objectMapper.convertValue(rules,
            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, com.aegisx.policy.dto.RuleResult.class));
        
        return PolicyEvaluationResponse.builder()
            .id(evaluation.getId())
            .transactionId(evaluation.getTransaction().getId())
            .decision(evaluation.getFinalDecision())
            .overallScore(evaluation.getOverallScore())
            .rules(rulesList)
            .processingDurationMs(evaluation.getProcessingDurationMs())
            .createdAt(java.time.LocalDateTime.ofInstant(evaluation.getCreatedAt(), java.time.ZoneOffset.UTC))
            .build();
    }
}
