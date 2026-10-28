package com.aegisx.policy.service;

import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.exception.MissingEvidenceException;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentStatus;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.entity.PolicyViolation;
import com.aegisx.policy.repository.PolicyEvaluationRepository;
import com.aegisx.policy.repository.PolicyViolationRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Policy evaluation integration")
class PolicyEvaluationServiceTest {

    @Autowired private PolicyEvaluationService service;
    @Autowired private PolicyEvaluationRepository evaluationRepository;
    @Autowired private PolicyViolationRepository violationRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentExtractionRepository extractionRepository;
    @Autowired private RequiredDocumentRepository requiredDocumentRepository;
    @Autowired private AIAnalysisRepository aiAnalysisRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private User merchant;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        merchant = userRepository.saveAndFlush(User.builder()
                .email("policy-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .fullName("Policy Test Merchant")
                .role(Role.MERCHANT)
                .build());
        transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-POLICY-" + UUID.randomUUID())
                .merchant(merchant)
                .beneficiaryName("XYZ Imports")
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .purposeCode("P0101")
                .status(TransactionStatus.COMPLIANCE_HOLD)
                .version(0L)
                .build());
    }

    @Test
    @DisplayName("fails with a domain error when the transaction does not exist")
    void testThrowWhenTransactionNotFound() {
        assertThatThrownBy(() -> service.evaluateTransaction(UUID.randomUUID()))
                .isInstanceOf(MissingEvidenceException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("fails closed when the transaction has no OCR extraction or AI assessment")
    void testMissingEvidenceFailsClosed() {
        assertThatThrownBy(() -> service.evaluateTransaction(transaction.getId()))
                .isInstanceOf(MissingEvidenceException.class)
                .hasMessageContaining("No OCR extraction");
    }

    @Test
    @DisplayName("persists an approved decision when every real rule passes")
    void testEvaluateAndTransitionToApproved() {
        populateEvaluationEvidence(true, true);
        transaction.applyStatus(TransactionStatus.POLICY_EVALUATION);
        transactionRepository.saveAndFlush(transaction);

        PolicyEvaluation result = service.evaluateTransaction(transaction.getId());

        assertThat(result.getId()).isNotNull();
        assertThat(result.getFinalDecision()).isEqualTo("APPROVED");
        assertThat(result.getTotalRulesCount()).isEqualTo(7);
        assertThat(result.getFailedRulesCount()).isZero();
        assertThat(result.getEvaluatedAt()).isNotNull();
        assertThat(evaluationRepository.findLatestByTransactionId(transaction.getId())).get()
                .extracting(PolicyEvaluation::getFinalDecision).isEqualTo("APPROVED");
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.APPROVED);
    }

    @Test
    @DisplayName("persists a compliance hold and each failed rule as a violation")
    void testEvaluateAndTransitionToComplianceHold() {
        populateEvaluationEvidence(false, false);

        PolicyEvaluation result = service.evaluateTransaction(transaction.getId());

        assertThat(result.getFinalDecision()).isEqualTo("COMPLIANCE_HOLD");
        assertThat(result.getFailedRulesCount()).isPositive();
        assertThat(result.getEvaluationJson().get("rules").isArray()).isTrue();
        assertThat(violationRepository.findByPolicyEvaluationId(result.getId())).isNotEmpty();
    }

    private void populateEvaluationEvidence(boolean allDocumentsFulfilled, boolean dataMatches) {
        Document document = documentRepository.saveAndFlush(Document.builder()
                .transaction(transaction)
                .documentType(DocumentType.INVOICE)
                .documentStatus(DocumentStatus.OCR_COMPLETED)
                .originalFilename("invoice.pdf")
                .storedFilename("invoice-" + UUID.randomUUID() + ".pdf")
                .mimeType("application/pdf")
                .fileSize(100L)
                .storagePath("policy-test/invoice.pdf")
                .uploadedBy(merchant)
                .version(1L)
                .build());
        for (DocumentType type : new DocumentType[]{
                DocumentType.INVOICE, DocumentType.SHIPPING_PROOF, DocumentType.EXPORT_DECLARATION}) {
            requiredDocumentRepository.saveAndFlush(RequiredDocument.builder()
                    .transaction(transaction)
                    .documentType(type)
                    .fulfilled(allDocumentsFulfilled)
                    .document(type == DocumentType.INVOICE ? document : null)
                    .build());
        }

        var data = objectMapper.createObjectNode()
                .put("beneficiary", dataMatches ? "XYZ Imports" : "Different Imports")
                .put("amount", dataMatches ? 2500.00 : 250.00)
                .put("currency", "USD");
        extractionRepository.saveAndFlush(DocumentExtraction.builder()
                .document(document)
                .extractedText("Invoice INV-123")
                .extractedData(data)
                .extractionMethod(DocumentExtraction.ExtractionMethod.TIKA)
                .confidenceScore(dataMatches ? 95.0 : 50.0)
                .processingDurationMs(5L)
                .ocrLanguage("eng")
                .build());
        DocumentExtraction extraction = extractionRepository.findLatestByTransactionId(transaction.getId())
                .orElseThrow();
        aiAnalysisRepository.saveAndFlush(AIAnalysis.builder()
                .transactionId(transaction.getId())
                .extractionId(extraction.getId())
                .modelName("deterministic-test-input")
                .promptVersion("integration-test")
                .inputHash("a".repeat(64))
                .analysisJson(objectMapper.createObjectNode().put("source", "test fixture"))
                .confidenceScore(dataMatches ? new BigDecimal("95.00") : new BigDecimal("50.00"))
                .processingDurationMs(0L)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
