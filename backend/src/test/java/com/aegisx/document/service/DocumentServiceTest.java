package com.aegisx.document.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentStatus;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Document service integration")
class DocumentServiceTest {

    @Autowired private DocumentService documentService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RequiredDocumentRepository requiredDocumentRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private TransactionService transactionService;

    private User merchant;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        merchant = userRepository.saveAndFlush(User.builder()
                .fullName("ABC Exports")
                .email("document-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-DOC-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Blue Star Imports")
                .purposeCode("P0001")
                .status(TransactionStatus.EVIDENCE_PENDING)
                .version(0L)
                .build());
    }

    @Test
    @DisplayName("initializes and persists all required evidence rows")
    void initializeChecklistCreatesRequiredEvidenceRowsForTransaction() {
        documentService.initializeChecklist(transaction.getId());

        assertThat(requiredDocumentRepository.findByTransactionId(transaction.getId()))
                .extracting(RequiredDocument::getDocumentType)
                .containsExactlyInAnyOrder(
                        DocumentType.INVOICE, DocumentType.SHIPPING_PROOF, DocumentType.EXPORT_DECLARATION);
    }

    @Test
    @DisplayName("reports the real persisted checklist state for the transaction owner")
    void evidenceStatusReflectsCompletedChecklist() {
        documentService.initializeChecklist(transaction.getId());

        var response = documentService.getEvidenceStatus(transaction.getId(), merchant.getEmail());

        assertThat(response.getTotalRequired()).isEqualTo(3);
        assertThat(response.getFulfilledCount()).isZero();
        assertThat(response.getRemainingCount()).isEqualTo(3);
        assertThat(response.isComplete()).isFalse();
    }

    @Test
    @DisplayName("a merchant cannot upload evidence to another merchant's transaction")
    void uploadRejectsTransactionOwnedByAnotherMerchant() {
        User otherMerchant = userRepository.saveAndFlush(User.builder()
                .fullName("Other Merchant")
                .email("document-other-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.pdf", "application/pdf", new byte[]{1, 2, 3});

        long documentsBefore = documentRepository.count();
        assertThatThrownBy(() -> documentService.uploadDocument(
                transaction.getId(), file, DocumentType.INVOICE, otherMerchant.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
        assertThat(documentRepository.count()).isEqualTo(documentsBefore);
    }

    @Test
    @DisplayName("a merchant cannot read another merchant's transaction or document")
    void readRejectsAnotherMerchantsTransactionAndDocument() {
        User otherMerchant = userRepository.saveAndFlush(User.builder()
                .fullName("Other Merchant")
                .email("document-other-read-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());
        Transaction otherTransaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-DOC-OTHER-" + UUID.randomUUID())
                .merchant(otherMerchant)
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .beneficiaryName("Blue Star Imports")
                .purposeCode("P0001")
                .status(TransactionStatus.EVIDENCE_PENDING)
                .version(0L)
                .build());
        Document document = documentRepository.saveAndFlush(Document.builder()
                .transaction(otherTransaction)
                .documentType(DocumentType.INVOICE)
                .documentStatus(DocumentStatus.UPLOADED)
                .originalFilename("invoice.pdf")
                .storedFilename("stored-invoice.pdf")
                .mimeType("application/pdf")
                .fileSize(100L)
                .storagePath("documents/other-merchant/stored-invoice.pdf")
                .uploadedBy(otherMerchant)
                .version(0L)
                .build());

        assertThatThrownBy(() -> transactionService.getTransaction(
                otherTransaction.getId(), merchant.getEmail(), Role.MERCHANT))
                .hasMessageContaining("Transaction not found");
        assertThatThrownBy(() -> documentService.listDocuments(
                otherTransaction.getId(), merchant.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
        assertThatThrownBy(() -> documentService.getDocument(
                otherTransaction.getId(), document.getId(), merchant.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
        assertThatThrownBy(() -> documentService.loadDocument(
                otherTransaction.getId(), document.getId(), merchant.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
        assertThatThrownBy(() -> documentService.getEvidenceStatus(
                otherTransaction.getId(), merchant.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
    }
}
