package com.aegisx.evidence.event;

import com.aegisx.evidence.service.EvidenceOrchestrationService;
import com.aegisx.transaction.entity.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Event listener for evidence-related events.
 * Automatically triggers evidence checklist creation and evaluation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceEventListener {

    private final EvidenceOrchestrationService evidenceOrchestrationService;

    /**
     * Handles transaction state change events to initialize evidence collection.
     */
    @EventListener
    @Async
    public void handleTransactionStateChange(TransactionStateChangeEvent event) {
        log.debug("Handling transaction state change event: {} -> {}", 
                 event.getPreviousState(), event.getNewState());
        
        // Initialize evidence checklist when payment is received
        if (event.getNewState() == TransactionStatus.PAYMENT_RECEIVED) {
            try {
                evidenceOrchestrationService.initializeEvidenceForTransaction(event.getTransactionId());
            } catch (Exception e) {
                log.error("Failed to initialize evidence for transaction: {}", event.getTransactionId(), e);
            }
        }
        
        // Handle evidence re-evaluation on state transitions
        if (event.getNewState() == TransactionStatus.EVIDENCE_PENDING) {
            try {
                evidenceOrchestrationService.forceEvidenceReevaluation(event.getTransactionId());
            } catch (Exception e) {
                log.error("Failed to re-evaluate evidence for transaction: {}", event.getTransactionId(), e);
            }
        }
    }

    /**
     * Handles document upload events to update evidence checklists.
     */
    @EventListener
    @Async
    public void handleDocumentUpload(DocumentUploadEvent event) {
        log.debug("Handling document upload event: {} for transaction: {}", 
                 event.getDocumentType(), event.getTransactionId());
        
        try {
            evidenceOrchestrationService.handleDocumentUpload(
                    event.getTransactionId(), 
                    event.getDocumentType()
            );
        } catch (Exception e) {
            log.error("Failed to handle document upload for transaction: {}", 
                     event.getTransactionId(), e);
        }
    }

    /**
     * Event representing a transaction state change.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TransactionStateChangeEvent {
        private UUID transactionId;
        private TransactionStatus previousState;
        private TransactionStatus newState;
        private String actor;
        private String reason;
    }

    /**
     * Event representing a document upload.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class DocumentUploadEvent {
        private UUID transactionId;
        private com.aegisx.document.entity.DocumentType documentType;
        private UUID documentId;
        private String uploadedBy;
    }
}