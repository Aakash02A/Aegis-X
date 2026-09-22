package com.aegisx.policy.controller;

import com.aegisx.common.exception.MissingEvidenceException;
import com.aegisx.common.exception.PolicyEvaluationException;
import com.aegisx.policy.dto.PolicyEvaluationResponse;
import com.aegisx.policy.service.PolicyEvaluationService;
import com.aegisx.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PolicyEvaluationController provides REST endpoints for policy evaluation.
 * 
 * Purpose:
 * - Trigger deterministic policy evaluation
 * - Retrieve final compliance decisions
 * - Support merchant isolation and compliance officer access
 * 
 * Endpoints:
 * - POST /api/transactions/{id}/policy-evaluation: Trigger evaluation
 * - GET /api/transactions/{id}/policy-evaluation: Retrieve latest decision
 * 
 * Security:
 * - JWT authentication required
 * - Merchant can view only their transactions
 * - Compliance Officer can view all evaluations
 * - Admin retains full access
 * 
 * Design:
 * - Fail-closed: missing evidence returns error (not APPROVED)
 * - Never expose internal rule engine details
 * - Return only final decision and evidence
 * - Support full audit trail retrieval
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class PolicyEvaluationController {
    
    private final PolicyEvaluationService policyEvaluationService;
    private final TransactionRepository transactionRepository;
    
    /**
     * Trigger policy evaluation for a transaction.
     * 
     * POST /api/transactions/{id}/policy-evaluation
     * 
     * @param transactionId Transaction to evaluate
     * @return Final compliance decision (APPROVED or COMPLIANCE_HOLD)
     */
    @PostMapping("/{id}/policy-evaluation")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> triggerEvaluation(@PathVariable("id") UUID transactionId) {
        try {
            log.info("Triggering policy evaluation for transaction: {}", transactionId);
            
            // Verify transaction exists
            transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            // Trigger evaluation
            var evaluation = policyEvaluationService.evaluateTransaction(transactionId);
            
            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "POLICY_EVALUATION");
            response.put("decision", evaluation.getFinalDecision());
            response.put("evaluationId", evaluation.getId());
            response.put("processingDurationMs", evaluation.getProcessingDurationMs());
            response.put("message", "Policy evaluation completed");
            
            log.info("Policy evaluation triggered successfully for transaction: {}, Decision: {}", 
                transactionId, evaluation.getFinalDecision());
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for policy evaluation: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", e.getMessage()
            ));
        } catch (MissingEvidenceException e) {
            log.warn("Missing evidence for policy evaluation of transaction {}: {}", transactionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Missing Evidence",
                "message", e.getMessage(),
                "transactionId", transactionId
            ));
        } catch (PolicyEvaluationException e) {
            log.error("Policy evaluation failed for transaction {}: {}", transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Policy Evaluation Failed",
                "message", e.getMessage(),
                "transactionId", transactionId
            ));
        } catch (Exception e) {
            log.error("Unexpected error during policy evaluation for transaction {}: {}", transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "An unexpected error occurred during policy evaluation"
            ));
        }
    }
    
    /**
     * Retrieve the latest policy evaluation for a transaction.
     * 
     * GET /api/transactions/{id}/policy-evaluation
     * 
     * @param transactionId Transaction to query
     * @return Latest evaluation with decision and rule results
     */
    @GetMapping("/{id}/policy-evaluation")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getLatestEvaluation(@PathVariable("id") UUID transactionId) {
        try {
            log.info("Retrieving latest policy evaluation for transaction: {}", transactionId);
            
            // Verify transaction exists
            transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
            
            // Retrieve latest evaluation
            PolicyEvaluationResponse evaluation = policyEvaluationService.getLatestEvaluation(transactionId);
            
            if (evaluation == null) {
                log.info("No policy evaluation found for transaction: {}", transactionId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Not Found",
                    "message", "No policy evaluation found for this transaction"
                ));
            }
            
            log.info("Retrieved policy evaluation for transaction: {}", transactionId);
            return ResponseEntity.ok(evaluation);
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for policy evaluation retrieval: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error retrieving policy evaluation for transaction {}: {}", transactionId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "An error occurred while retrieving evaluation"
            ));
        }
    }
}
