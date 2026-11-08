package com.aegisx.policy.service;

import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.entity.AuditEvent;
import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.repository.AuditEventRepository;
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
import java.time.Instant;
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
    @Autowired private AuditEventRepository auditEventRepository;
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
    @DisplayName("fails closed when the transaction has no OCR extraction")
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

        Instant decisionStartedAt = Instant.now();
        PolicyEvaluation result = service.evaluateTransaction(transaction.getId());

        assertThat(result.getId()).isNotNull();
        assertThat(result.getFinalDecision()).isEqualTo("APPROVED");
        assertThat(result.getTotalRulesCount()).isEqualTo(5);
        assertThat(result.getFailedRulesCount()).isZero();
        assertThat(result.getEvaluatedAt()).isNotNull();
        assertThat(evaluationRepository.findLatestByTransactionId(transaction.getId())).get()
                .extracting(PolicyEvaluation::getFinalDecision).isEqualTo("APPROVED");
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.APPROVED);
        assertPolicyDecisionAudit(transaction, "APPROVED", TransactionStatus.POLICY_EVALUATION, decisionStartedAt);
    }

    @Test
    @DisplayName("persists a compliance hold and each failed rule as a violation")
    void testEvaluateAndTransitionToComplianceHold() {
        populateEvaluationEvidence(false, false);

        Instant decisionStartedAt = Instant.now();
        PolicyEvaluation result = service.evaluateTransaction(transaction.getId());

        assertThat(result.getFinalDecision()).isEqualTo("COMPLIANCE_HOLD");
        assertThat(result.getFailedRulesCount()).isPositive();
        assertThat(result.getEvaluationJson().get("rules").isArray()).isTrue();
        assertThat(violationRepository.findByPolicyEvaluationId(result.getId())).isNotEmpty();
        assertPolicyDecisionAudit(transaction, "COMPLIANCE_HOLD", TransactionStatus.COMPLIANCE_HOLD,
                decisionStartedAt);
    }

    private void assertPolicyDecisionAudit(Transaction target, String decision, TransactionStatus previousStatus,
                                          Instant decisionStartedAt) {
        AuditEvent event = auditEventRepository.findByTransactionIdOrderByCreatedAtAsc(target.getId()).stream()
                .filter(candidate -> candidate.getEventType() == (decision.equals("APPROVED")
                        ? AuditEventType.POLICY_APPROVED : AuditEventType.POLICY_HOLD))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing policy decision audit for " + decision));

        assertThat(event.getNewState()).isEqualTo(decision.equals("APPROVED")
                ? TransactionStatus.APPROVED.name() : TransactionStatus.COMPLIANCE_HOLD.name());
        assertThat(event.getModule()).isEqualTo(AuditModule.POLICY_ENGINE);
        assertThat(event.getActorType()).isEqualTo(ActorType.POLICY_ENGINE);
        assertThat(event.getActorId()).isNull();
        assertThat(event.getPreviousState()).isEqualTo(previousStatus.name());
        assertThat(event.getEventData().path("decision").asText()).isEqualTo(decision);
        assertThat(event.getEventData().path("justification").asText()).isNotBlank();
        assertThat(event.getEventData().path("rulesEvaluated")).hasSize(5);
        assertThat(event.getCreatedAt()).isNotNull().isAfterOrEqualTo(decisionStartedAt)
                .isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("AI availability and confidence do not change a decision from the same verified fields")
    void policyDecisionIsIndependentOfAiState() {
        record Scenario(String label, TransactionStatus startingStatus, BigDecimal aiConfidence) { }
        var scenarios = java.util.List.of(
                new Scenario("high AI confidence", TransactionStatus.POLICY_EVALUATION, new BigDecimal("99.00")),
                new Scenario("low AI confidence", TransactionStatus.POLICY_EVALUATION, new BigDecimal("1.00")),
                new Scenario("AI disabled", TransactionStatus.AI_ANALYSIS, null),
                new Scenario("AI failed", TransactionStatus.AI_ANALYSIS_FAILED, null),
                new Scenario("AI timed out", TransactionStatus.AI_ANALYSIS_FAILED, null),
                new Scenario("AI output rejected as garbage", TransactionStatus.AI_ANALYSIS_FAILED, null));

        for (Scenario scenario : scenarios) {
            Transaction candidate = equivalentTransaction(scenario.startingStatus());
            populateEvaluationEvidence(candidate, true, true, scenario.aiConfidence());

            PolicyEvaluation result = service.evaluateTransaction(candidate.getId());

            assertThat(result.getFinalDecision()).as(scenario.label()).isEqualTo("APPROVED");
            assertThat(result.getTotalRulesCount()).as(scenario.label()).isEqualTo(5);
            assertThat(result.getOverallScore()).as(scenario.label() + " must not surface AI confidence as a policy score").isNull();
            assertThat(transactionRepository.findById(candidate.getId()).orElseThrow().getStatus())
                    .as(scenario.label() + " still completes the policy transition")
                    .isEqualTo(TransactionStatus.APPROVED);
            if (scenario.aiConfidence() == null) {
                assertThat(result.getAiAnalysis()).as(scenario.label() + " must not invent an AI result").isNull();
            }
        }
    }

    @Test
    @DisplayName("malformed legacy AI content cannot set a policy decision")
    void malformedAiRecordCannotSetPolicyDecision() {
        Transaction candidate = equivalentTransaction(TransactionStatus.POLICY_EVALUATION);
        populateEvaluationEvidence(candidate, true, true, null);
        DocumentExtraction extraction = extractionRepository.findLatestByTransactionId(candidate.getId()).orElseThrow();
        aiAnalysisRepository.saveAndFlush(AIAnalysis.builder()
                .transactionId(candidate.getId())
                .extractionId(extraction.getId())
                .modelName("legacy-malformed-fixture")
                .promptVersion("legacy-fixture")
                .inputHash(UUID.randomUUID().toString().replace("-", "").repeat(2))
                .analysisJson(objectMapper.createObjectNode()
                        .put("decision", "REJECTED")
                        .put("overallConfidence", "garbage"))
                .confidenceScore(BigDecimal.ZERO)
                .processingDurationMs(0L)
                .createdAt(LocalDateTime.now())
                .build());

        PolicyEvaluation result = service.evaluateTransaction(candidate.getId());

        assertThat(result.getFinalDecision()).isEqualTo("APPROVED");
        assertThat(result.getTotalRulesCount()).isEqualTo(5);
        assertThat(result.getOverallScore()).isNull();
        assertThat(result.getAiAnalysis()).isNotNull();
    }

    private void populateEvaluationEvidence(boolean allDocumentsFulfilled, boolean dataMatches) {
        populateEvaluationEvidence(transaction, allDocumentsFulfilled, dataMatches,
                dataMatches ? new BigDecimal("95.00") : new BigDecimal("50.00"));
    }

    private Transaction equivalentTransaction(TransactionStatus startingStatus) {
        return transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-POLICY-" + UUID.randomUUID())
                .merchant(merchant)
                .beneficiaryName("XYZ Imports")
                .amount(new BigDecimal("2500.00"))
                .currency("USD")
                .purposeCode("P0101")
                .status(startingStatus)
                .version(0L)
                .build());
    }

    private void populateEvaluationEvidence(Transaction target, boolean allDocumentsFulfilled,
                                             boolean dataMatches, BigDecimal aiConfidence) {
        Document document = documentRepository.saveAndFlush(Document.builder()
                .transaction(target)
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
                    .transaction(target)
                    .documentType(type)
                    .fulfilled(allDocumentsFulfilled)
                    .document(type == DocumentType.INVOICE ? document : null)
                    .build());
        }

        var data = objectMapper.createObjectNode()
                .put("beneficiary", dataMatches ? "XYZ Imports" : "Different Imports")
                .put("amount", dataMatches ? 2500.00 : 250.00)
                .put("currency", "USD");
        DocumentExtraction extraction = extractionRepository.saveAndFlush(DocumentExtraction.builder()
                .document(document)
                .extractedText("Invoice INV-123")
                .extractedData(data)
                .extractionMethod(DocumentExtraction.ExtractionMethod.TIKA)
                .confidenceScore(dataMatches ? 95.0 : 50.0)
                .processingDurationMs(5L)
                .ocrLanguage("eng")
                .build());
        if (aiConfidence != null) {
            aiAnalysisRepository.saveAndFlush(AIAnalysis.builder()
                    .transactionId(target.getId())
                    .extractionId(extraction.getId())
                    .modelName("deterministic-test-input")
                    .promptVersion("integration-test")
                    .inputHash(UUID.randomUUID().toString().replace("-", "").repeat(2))
                    .analysisJson(objectMapper.createObjectNode().put("source", "test fixture"))
                    .confidenceScore(aiConfidence)
                    .processingDurationMs(0L)
                    .createdAt(LocalDateTime.now())
                    .build());
        }
    }
}
