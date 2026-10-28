package com.aegisx.evidence.service;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.evidence.repository.EvidenceChecklistRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Evidence checklist service integration")
class EvidenceChecklistServiceTest {

    @Autowired private EvidenceChecklistService evidenceChecklistService;
    @Autowired private EvidenceChecklistRepository evidenceChecklistRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        User merchant = userRepository.saveAndFlush(User.builder()
                .email("evidence-checklist-" + UUID.randomUUID() + "@aegis-x.test")
                .fullName("Test Merchant")
                .passwordHash("not-used-by-this-service")
                .role(Role.MERCHANT)
                .build());

        transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-TEST-" + UUID.randomUUID())
                .merchant(merchant)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .purposeCode("P0101")
                .beneficiaryName("Test Beneficiary")
                .status(TransactionStatus.EVIDENCE_PENDING)
                .build());
    }

    @Test
    @DisplayName("creates and persists a checklist when none exists")
    void shouldCreateNewChecklistWhenNoneExists() {
        EvidenceChecklist result = evidenceChecklistService.getOrCreateChecklist(transaction);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getTransaction().getId()).isEqualTo(transaction.getId());
        assertThat(evidenceChecklistRepository.findByTransactionId(transaction.getId()))
                .get()
                .extracting(EvidenceChecklist::getId)
                .isEqualTo(result.getId());
    }

    @Test
    @DisplayName("returns an existing persisted checklist")
    void shouldReturnExistingChecklistWhenFound() {
        EvidenceChecklist existing = evidenceChecklistRepository.saveAndFlush(newChecklist());

        EvidenceChecklist result = evidenceChecklistService.getOrCreateChecklist(transaction);

        assertThat(result.getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("marks a commercial invoice as uploaded and reevaluates satisfaction")
    void shouldMarkCommercialInvoiceAsUploaded() {
        evidenceChecklistRepository.saveAndFlush(newChecklist());

        EvidenceChecklist result = evidenceChecklistService.markDocumentUploaded(
                transaction.getId(), DocumentType.COMMERCIAL_INVOICE);

        assertThat(result.getInvoiceUploaded()).isTrue();
        assertThat(result.isSatisfied()).isTrue();
        assertThat(evidenceChecklistRepository.findByTransactionId(transaction.getId()).orElseThrow()
                .getInvoiceUploaded()).isTrue();
    }

    @Test
    @DisplayName("maps the legacy invoice type to the commercial invoice")
    void shouldHandleLegacyInvoiceTypeMapping() {
        evidenceChecklistRepository.saveAndFlush(newChecklist());

        EvidenceChecklist result = evidenceChecklistService.markDocumentUploaded(
                transaction.getId(), DocumentType.INVOICE);

        assertThat(result.getInvoiceUploaded()).isTrue();
    }

    @Test
    @DisplayName("identifies each required document that is missing")
    void shouldIdentifyMissingDocumentsCorrectly() {
        EvidenceChecklist checklist = newChecklist();
        checklist.setShippingBillRequired(true);
        checklist.setPackingListRequired(true);
        evidenceChecklistRepository.saveAndFlush(checklist);

        List<DocumentType> missing = evidenceChecklistService.getMissingDocuments(transaction.getId());

        assertThat(missing).containsExactlyInAnyOrder(
                DocumentType.COMMERCIAL_INVOICE, DocumentType.SHIPPING_BILL, DocumentType.PACKING_LIST);
    }

    @Test
    @DisplayName("returns no missing documents when all required documents are uploaded")
    void shouldReturnEmptyListWhenAllDocumentsUploaded() {
        EvidenceChecklist checklist = newChecklist();
        checklist.setInvoiceUploaded(true);
        checklist.setShippingBillRequired(true);
        checklist.setShippingBillUploaded(true);
        checklist.evaluateSatisfaction();
        evidenceChecklistRepository.saveAndFlush(checklist);

        assertThat(evidenceChecklistService.getMissingDocuments(transaction.getId())).isEmpty();
    }

    @Test
    @DisplayName("checks evidence completeness using the persisted checklist")
    void shouldCheckEvidenceCompletenessCorrectly() {
        EvidenceChecklist checklist = newChecklist();
        checklist.setInvoiceUploaded(true);
        checklist.evaluateSatisfaction();
        evidenceChecklistRepository.saveAndFlush(checklist);

        assertThat(evidenceChecklistService.isEvidenceComplete(transaction.getId())).isTrue();
    }

    @Test
    @DisplayName("persists custom evidence requirements")
    void shouldSetCustomRequirementsCorrectly() {
        evidenceChecklistRepository.saveAndFlush(newChecklist());

        EvidenceChecklist result = evidenceChecklistService.setCustomRequirements(
                transaction.getId(), true, true, false, true);

        EvidenceChecklist persisted = evidenceChecklistRepository.findByTransactionId(transaction.getId())
                .orElseThrow();
        assertThat(result.getId()).isEqualTo(persisted.getId());
        assertThat(persisted.getInvoiceRequired()).isTrue();
        assertThat(persisted.getShippingBillRequired()).isTrue();
        assertThat(persisted.getPackingListRequired()).isFalse();
        assertThat(persisted.getCertificateOriginRequired()).isTrue();
    }

    @Test
    @DisplayName("throws when marking a document without a checklist")
    void shouldThrowExceptionWhenChecklistNotFoundForMarkingDocument() {
        assertThatThrownBy(() -> evidenceChecklistService.markDocumentUploaded(
                UUID.randomUUID(), DocumentType.COMMERCIAL_INVOICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reevaluates and persists checklist satisfaction")
    void shouldReEvaluateChecklistSatisfaction() {
        evidenceChecklistRepository.saveAndFlush(newChecklist());

        EvidenceChecklist result = evidenceChecklistService.reevaluateChecklist(transaction.getId());

        assertThat(result.isSatisfied()).isFalse();
        assertThat(result.getEvaluatedAt()).isNotNull();
        assertThat(evidenceChecklistRepository.findByTransactionId(transaction.getId()).orElseThrow()
                .isSatisfied()).isFalse();
    }

    @Test
    @DisplayName("defaults to requiring a commercial invoice when no checklist exists")
    void shouldReturnDefaultRequirementsWhenNoChecklistExists() {
        assertThat(evidenceChecklistService.getMissingDocuments(UUID.randomUUID()))
                .containsExactly(DocumentType.COMMERCIAL_INVOICE);
    }

    private EvidenceChecklist newChecklist() {
        EvidenceChecklist checklist = EvidenceChecklist.builder()
                .transaction(transaction)
                .invoiceRequired(true)
                .invoiceUploaded(false)
                .isSatisfied(false)
                .build();
        checklist.evaluateSatisfaction();
        return checklist;
    }
}
