package com.aegisx.evidence.service;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.service.TransactionService;
import com.aegisx.transaction.service.TransactionStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestration service for evidence collection workflow.
 * Coordinates between evidence checklists, document uploads, and transaction state machine.
 * Implements the evidence workflow from SRS Section 5.1.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EvidenceOrchestrationService {

    private final EvidenceChecklistService evidenceChecklistService;
    private final TransactionService transactionService;
    private final TransactionStateMachine stateMachine;

    /**
     * Initializes evidence checklist for a new transaction.
     * Called when a transaction moves to PAYMENT_RECEIVED state.
     */
    public EvidenceChecklist initializeEvidenceForTransaction(UUID transactionId) {
        log.info("Initializing evidence checklist for transaction: {}", transactionId);
        
        Transaction transaction = transactionService.getTransactionById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        
        EvidenceChecklist checklist = evidenceChecklistService.getOrCreateChecklist(transaction);
        
        // Transition to EVIDENCE_PENDING if evidence is not complete
        if (!checklist.isSatisfied() && transaction.getStatus() == TransactionStatus.PAYMENT_RECEIVED) {
            stateMachine.transition(transaction, TransactionStatus.EVIDENCE_PENDING, 
                    TransitionActor.EVIDENCE_SERVICE, "Evidence collection required");
            transactionService.updateTransaction(transaction);
        }
        
        return checklist;
    }

    /**
     * Handles document upload event and updates evidence checklist.
     * Called by document upload service after successful upload.
     */
    public void handleDocumentUpload(UUID transactionId, DocumentType documentType) {
        log.info("Handling document upload for transaction: {}, type: {}", transactionId, documentType);
        
        // Update evidence checklist
        EvidenceChecklist checklist = evidenceChecklistService.markDocumentUploaded(transactionId, documentType);
        
        // Check if evidence is now complete and advance transaction state
        if (checklist.isSatisfied()) {
            handleEvidenceCompletion(transactionId);
        }
    }

    /**
     * Handles evidence completion and advances transaction to OCR processing.
     */
    private void handleEvidenceCompletion(UUID transactionId) {
        log.info("Evidence checklist satisfied for transaction: {}, advancing to OCR processing", transactionId);
        
        Transaction transaction = transactionService.getTransactionById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        
        // Only advance if currently in EVIDENCE_PENDING state
        if (transaction.getStatus() == TransactionStatus.EVIDENCE_PENDING) {
            stateMachine.transition(transaction, TransactionStatus.OCR_PROCESSING,
                    TransitionActor.EVIDENCE_SERVICE, "All required documents uploaded");
            transactionService.updateTransaction(transaction);
        }
    }

    /**
     * Evaluates evidence requirements and returns next actions needed.
     */
    @Transactional(readOnly = true)
    public EvidenceEvaluationResult evaluateEvidenceStatus(UUID transactionId) {
        List<DocumentType> missingDocuments = evidenceChecklistService.getMissingDocuments(transactionId);
        boolean isComplete = evidenceChecklistService.isEvidenceComplete(transactionId);
        
        Transaction transaction = transactionService.getTransactionById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        
        return EvidenceEvaluationResult.builder()
                .transactionId(transactionId)
                .currentStatus(transaction.getStatus())
                .evidenceComplete(isComplete)
                .missingDocuments(missingDocuments)
                .nextAction(determineNextAction(transaction.getStatus(), isComplete, missingDocuments))
                .canProceed(isComplete && transaction.getStatus() == TransactionStatus.EVIDENCE_PENDING)
                .build();
    }

    /**
     * Determines the next action based on current state and evidence status.
     */
    private NextAction determineNextAction(TransactionStatus currentStatus, boolean evidenceComplete, 
                                         List<DocumentType> missingDocuments) {
        if (evidenceComplete) {
            return switch (currentStatus) {
                case EVIDENCE_PENDING -> NextAction.PROCEED_TO_OCR;
                case OCR_PROCESSING, AI_ANALYSIS, POLICY_EVALUATION -> NextAction.WAIT_FOR_PROCESSING;
                case APPROVED -> NextAction.COMPLETE;
                case COMPLIANCE_HOLD -> NextAction.AWAIT_REVIEW;
                case REJECTED -> NextAction.REJECTED;
                default -> NextAction.WAIT;
            };
        } else {
            return missingDocuments.isEmpty() ? NextAction.WAIT : NextAction.UPLOAD_DOCUMENTS;
        }
    }

    /**
     * Forces re-evaluation of evidence and advances state if possible.
     */
    public void forceEvidenceReevaluation(UUID transactionId) {
        log.info("Forcing evidence re-evaluation for transaction: {}", transactionId);
        
        EvidenceChecklist checklist = evidenceChecklistService.reevaluateChecklist(transactionId);
        
        if (checklist.isSatisfied()) {
            handleEvidenceCompletion(transactionId);
        }
    }

    /**
     * Handles document requirement changes by compliance officers.
     */
    public void handleRequirementChange(UUID transactionId, String reason) {
        log.info("Evidence requirements changed for transaction: {}, reason: {}", transactionId, reason);
        
        // Re-evaluate evidence after requirement change
        EvidenceChecklist checklist = evidenceChecklistService.reevaluateChecklist(transactionId);
        
        Transaction transaction = transactionService.getTransactionById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        
        // If evidence was complete but now incomplete due to new requirements, revert to EVIDENCE_PENDING
        if (!checklist.isSatisfied() && 
            (transaction.getStatus() == TransactionStatus.OCR_PROCESSING || 
             transaction.getStatus() == TransactionStatus.AI_ANALYSIS)) {
            
            stateMachine.transition(transaction, TransactionStatus.EVIDENCE_PENDING,
                    TransitionActor.COMPLIANCE_OFFICER, "Requirements changed: " + reason);
            transactionService.updateTransaction(transaction);
        }
    }

    /**
     * Result of evidence evaluation.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EvidenceEvaluationResult {
        private UUID transactionId;
        private TransactionStatus currentStatus;
        private boolean evidenceComplete;
        private List<DocumentType> missingDocuments;
        private NextAction nextAction;
        private boolean canProceed;
    }

    /**
     * Next action recommendations.
     */
    public enum NextAction {
        UPLOAD_DOCUMENTS("Upload required documents"),
        PROCEED_TO_OCR("Proceed to OCR processing"),
        WAIT_FOR_PROCESSING("Wait for system processing"),
        AWAIT_REVIEW("Awaiting compliance review"),
        COMPLETE("Transaction complete"),
        REJECTED("Transaction rejected"),
        WAIT("Wait for system action");

        private final String description;

        NextAction(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}