package com.aegisx.evidence.service;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.evidence.repository.EvidenceChecklistRepository;
import com.aegisx.transaction.entity.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing evidence checklists and document requirements.
 * Implements evidence checklist management according to SRS Section 5.1.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EvidenceChecklistService {

    private final EvidenceChecklistRepository evidenceChecklistRepository;

    /**
     * Creates or retrieves an evidence checklist for a transaction.
     */
    public EvidenceChecklist getOrCreateChecklist(Transaction transaction) {
        Optional<EvidenceChecklist> existing = evidenceChecklistRepository.findByTransaction(transaction);
        
        if (existing.isPresent()) {
            log.debug("Found existing evidence checklist for transaction: {}", transaction.getId());
            return existing.get();
        }
        
        log.info("Creating new evidence checklist for transaction: {}", transaction.getId());
        EvidenceChecklist checklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .build();
        
        // Set requirements based on transaction characteristics
        checklist.setRequirementsBasedOnTransaction(transaction);
        
        return evidenceChecklistRepository.save(checklist);
    }

    /**
     * Updates checklist when a document is uploaded.
     */
    public EvidenceChecklist markDocumentUploaded(UUID transactionId, DocumentType documentType) {
        EvidenceChecklist checklist = evidenceChecklistRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No evidence checklist found for transaction: " + transactionId));
        
        log.info("Marking document {} as uploaded for transaction: {}", documentType, transactionId);
        checklist.markDocumentUploaded(documentType);
        
        EvidenceChecklist saved = evidenceChecklistRepository.save(checklist);
        
        if (saved.isSatisfied()) {
            log.info("Evidence checklist satisfied for transaction: {}", transactionId);
        } else {
            log.debug("Evidence checklist still pending for transaction: {}", transactionId);
        }
        
        return saved;
    }

    /**
     * Checks if evidence is complete for a transaction.
     */
    @Transactional(readOnly = true)
    public boolean isEvidenceComplete(UUID transactionId) {
        return evidenceChecklistRepository.isChecklistSatisfiedForTransaction(transactionId);
    }

    /**
     * Gets evidence checklist by transaction ID.
     */
    @Transactional(readOnly = true)
    public Optional<EvidenceChecklist> getChecklistByTransactionId(UUID transactionId) {
        return evidenceChecklistRepository.findByTransactionId(transactionId);
    }

    /**
     * Re-evaluates checklist satisfaction status.
     */
    public EvidenceChecklist reevaluateChecklist(UUID transactionId) {
        EvidenceChecklist checklist = evidenceChecklistRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No evidence checklist found for transaction: " + transactionId));
        
        log.debug("Re-evaluating evidence checklist for transaction: {}", transactionId);
        checklist.evaluateSatisfaction();
        
        return evidenceChecklistRepository.save(checklist);
    }

    /**
     * Sets custom requirements for a transaction.
     */
    public EvidenceChecklist setCustomRequirements(UUID transactionId, 
                                                   boolean invoiceRequired,
                                                   boolean shippingBillRequired, 
                                                   boolean packingListRequired,
                                                   boolean certificateOriginRequired) {
        EvidenceChecklist checklist = evidenceChecklistRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No evidence checklist found for transaction: " + transactionId));
        
        log.info("Setting custom requirements for transaction: {}", transactionId);
        checklist.setInvoiceRequired(invoiceRequired);
        checklist.setShippingBillRequired(shippingBillRequired);
        checklist.setPackingListRequired(packingListRequired);
        checklist.setCertificateOriginRequired(certificateOriginRequired);
        
        checklist.evaluateSatisfaction();
        
        return evidenceChecklistRepository.save(checklist);
    }

    /**
     * Gets missing document types for a transaction.
     */
    @Transactional(readOnly = true)
    public java.util.List<DocumentType> getMissingDocuments(UUID transactionId) {
        EvidenceChecklist checklist = evidenceChecklistRepository.findByTransactionId(transactionId)
                .orElse(null);
        
        if (checklist == null) {
            return java.util.List.of(DocumentType.COMMERCIAL_INVOICE); // Default requirement
        }
        
        java.util.List<DocumentType> missing = new java.util.ArrayList<>();
        
        if (Boolean.TRUE.equals(checklist.getInvoiceRequired()) && 
            !Boolean.TRUE.equals(checklist.getInvoiceUploaded())) {
            missing.add(DocumentType.COMMERCIAL_INVOICE);
        }
        
        if (Boolean.TRUE.equals(checklist.getShippingBillRequired()) && 
            !Boolean.TRUE.equals(checklist.getShippingBillUploaded())) {
            missing.add(DocumentType.SHIPPING_BILL);
        }
        
        if (Boolean.TRUE.equals(checklist.getPackingListRequired()) && 
            !Boolean.TRUE.equals(checklist.getPackingListUploaded())) {
            missing.add(DocumentType.PACKING_LIST);
        }
        
        if (Boolean.TRUE.equals(checklist.getCertificateOriginRequired()) && 
            !Boolean.TRUE.equals(checklist.getCertificateOriginUploaded())) {
            missing.add(DocumentType.CERTIFICATE_OF_ORIGIN);
        }
        
        return missing;
    }

    /**
     * Determines document requirements based on transaction characteristics.
     * This implements the business logic for evidence requirements.
     */
    private void setRequirementsBasedOnTransactionCharacteristics(EvidenceChecklist checklist, Transaction transaction) {
        // Always require commercial invoice
        checklist.setInvoiceRequired(true);
        
        // Shipping bill required for transactions > $5000
        if (transaction.getAmount().compareTo(new BigDecimal("5000")) > 0) {
            checklist.setShippingBillRequired(true);
            log.debug("Shipping bill required for high-value transaction: {} (amount: {})", 
                     transaction.getId(), transaction.getAmount());
        }
        
        // Packing list required for goods transactions with specific purpose codes
        String purposeCode = transaction.getPurposeCode();
        if (purposeCode != null && (purposeCode.startsWith("P01") || purposeCode.startsWith("P02"))) {
            checklist.setPackingListRequired(true);
            log.debug("Packing list required for goods transaction: {} (purpose: {})", 
                     transaction.getId(), purposeCode);
        }
        
        // Certificate of origin for international trade (can be enhanced based on country codes)
        if (transaction.getAmount().compareTo(new BigDecimal("10000")) > 0) {
            checklist.setCertificateOriginRequired(true);
            log.debug("Certificate of origin required for large international transaction: {} (amount: {})", 
                     transaction.getId(), transaction.getAmount());
        }
    }
}