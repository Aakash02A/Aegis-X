package com.aegisx.evidence.service;

import com.aegisx.auth.entity.User;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.evidence.repository.EvidenceChecklistRepository;
import com.aegisx.transaction.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceChecklistServiceTest {

    @Mock
    private EvidenceChecklistRepository evidenceChecklistRepository;

    @InjectMocks
    private EvidenceChecklistService evidenceChecklistService;

    private Transaction transaction;
    private EvidenceChecklist evidenceChecklist;
    private User merchant;

    @BeforeEach
    void setUp() {
        merchant = User.builder()
                .fullName("test-merchant")
                .fullName("Test Merchant")
                .build();

        transaction = Transaction.builder()
                .referenceNumber("TXN-TEST-001")
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .purposeCode("P0101")
                .beneficiaryName("Test Beneficiary")
                .build();

        evidenceChecklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(true)
                .invoiceUploaded(false)
                .isSatisfied(false)
                .build();
    }

    @Test
    @DisplayName("Should create new checklist when none exists")
    void shouldCreateNewChecklistWhenNoneExists() {
        when(evidenceChecklistRepository.findByTransaction(transaction)).thenReturn(Optional.empty());
        when(evidenceChecklistRepository.save(any(EvidenceChecklist.class))).thenReturn(evidenceChecklist);

        EvidenceChecklist result = evidenceChecklistService.getOrCreateChecklist(transaction);

        assertNotNull(result);
        assertEquals(transaction, result.getTransaction());
        verify(evidenceChecklistRepository).save(any(EvidenceChecklist.class));
    }

    @Test
    @DisplayName("Should return existing checklist when found")
    void shouldReturnExistingChecklistWhenFound() {
        when(evidenceChecklistRepository.findByTransaction(transaction)).thenReturn(Optional.of(evidenceChecklist));

        EvidenceChecklist result = evidenceChecklistService.getOrCreateChecklist(transaction);

        assertEquals(evidenceChecklist, result);
        verify(evidenceChecklistRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should mark commercial invoice as uploaded")
    void shouldMarkCommercialInvoiceAsUploaded() {
        UUID transactionId = transaction.getId();
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.of(evidenceChecklist));
        
        EvidenceChecklist updatedChecklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(true)
                .invoiceUploaded(true)
                .isSatisfied(true)
                .build();
        
        when(evidenceChecklistRepository.save(any(EvidenceChecklist.class))).thenReturn(updatedChecklist);

        EvidenceChecklist result = evidenceChecklistService.markDocumentUploaded(transactionId, DocumentType.COMMERCIAL_INVOICE);

        assertTrue(result.getInvoiceUploaded());
        assertTrue(result.isSatisfied());
        verify(evidenceChecklistRepository).save(any(EvidenceChecklist.class));
    }

    @Test
    @DisplayName("Should handle legacy INVOICE type mapping to COMMERCIAL_INVOICE")
    void shouldHandleLegacyInvoiceTypeMapping() {
        UUID transactionId = transaction.getId();
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.of(evidenceChecklist));
        when(evidenceChecklistRepository.save(any(EvidenceChecklist.class))).thenReturn(evidenceChecklist);

        // Should work with legacy INVOICE type
        assertDoesNotThrow(() -> 
            evidenceChecklistService.markDocumentUploaded(transactionId, DocumentType.INVOICE)
        );
    }

    @Test
    @DisplayName("Should identify missing documents correctly")
    void shouldIdentifyMissingDocumentsCorrectly() {
        UUID transactionId = transaction.getId();
        
        // Set up checklist with multiple requirements
        evidenceChecklist.setShippingBillRequired(true);
        evidenceChecklist.setPackingListRequired(true);
        
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.of(evidenceChecklist));

        List<DocumentType> missing = evidenceChecklistService.getMissingDocuments(transactionId);

        assertEquals(3, missing.size());
        assertTrue(missing.contains(DocumentType.COMMERCIAL_INVOICE));
        assertTrue(missing.contains(DocumentType.SHIPPING_BILL));
        assertTrue(missing.contains(DocumentType.PACKING_LIST));
    }

    @Test
    @DisplayName("Should return empty list when all documents uploaded")
    void shouldReturnEmptyListWhenAllDocumentsUploaded() {
        UUID transactionId = transaction.getId();
        
        // Set all required documents as uploaded
        evidenceChecklist.setInvoiceUploaded(true);
        evidenceChecklist.setShippingBillRequired(true);
        evidenceChecklist.setShippingBillUploaded(true);
        evidenceChecklist.setIsSatisfied(true);
        
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.of(evidenceChecklist));

        List<DocumentType> missing = evidenceChecklistService.getMissingDocuments(transactionId);

        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("Should check evidence completeness correctly")
    void shouldCheckEvidenceCompletenessCorrectly() {
        UUID transactionId = transaction.getId();
        
        when(evidenceChecklistRepository.isChecklistSatisfiedForTransaction(transactionId)).thenReturn(true);

        boolean isComplete = evidenceChecklistService.isEvidenceComplete(transactionId);

        assertTrue(isComplete);
        verify(evidenceChecklistRepository).isChecklistSatisfiedForTransaction(transactionId);
    }

    @Test
    @DisplayName("Should set custom requirements correctly")
    void shouldSetCustomRequirementsCorrectly() {
        UUID transactionId = transaction.getId();
        
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.of(evidenceChecklist));
        when(evidenceChecklistRepository.save(any(EvidenceChecklist.class))).thenReturn(evidenceChecklist);

        EvidenceChecklist result = evidenceChecklistService.setCustomRequirements(
                transactionId, true, true, false, true);

        assertNotNull(result);
        verify(evidenceChecklistRepository).save(any(EvidenceChecklist.class));
    }

    @Test
    @DisplayName("Should throw exception when checklist not found for marking document")
    void shouldThrowExceptionWhenChecklistNotFoundForMarkingDocument() {
        UUID transactionId = UUID.randomUUID();
        
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, 
                () -> evidenceChecklistService.markDocumentUploaded(transactionId, DocumentType.COMMERCIAL_INVOICE));
    }

    @Test
    @DisplayName("Should re-evaluate checklist satisfaction")
    void shouldReEvaluateChecklistSatisfaction() {
        UUID transactionId = transaction.getId();
        
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.of(evidenceChecklist));
        when(evidenceChecklistRepository.save(any(EvidenceChecklist.class))).thenReturn(evidenceChecklist);

        EvidenceChecklist result = evidenceChecklistService.reevaluateChecklist(transactionId);

        assertNotNull(result);
        verify(evidenceChecklistRepository).save(evidenceChecklist);
    }

    @Test
    @DisplayName("Should return default requirements when no checklist exists")
    void shouldReturnDefaultRequirementsWhenNoChecklistExists() {
        UUID transactionId = UUID.randomUUID();
        
        when(evidenceChecklistRepository.findByTransactionId(transactionId)).thenReturn(Optional.empty());

        List<DocumentType> missing = evidenceChecklistService.getMissingDocuments(transactionId);

        assertEquals(1, missing.size());
        assertTrue(missing.contains(DocumentType.COMMERCIAL_INVOICE));
    }
}