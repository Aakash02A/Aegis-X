package com.aegisx.document.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Document service integration")
class DocumentServiceTest {

    @Autowired private DocumentService documentService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RequiredDocumentRepository requiredDocumentRepository;

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
}
