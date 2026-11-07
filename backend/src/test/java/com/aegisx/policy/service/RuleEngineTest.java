package com.aegisx.policy.service;

import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.policy.rules.AmountConsistencyRule;
import com.aegisx.policy.rules.BeneficiaryMatchRule;
import com.aegisx.policy.rules.CurrencyConsistencyRule;
import com.aegisx.policy.rules.DocumentCompletenessRule;
import com.aegisx.policy.rules.PurposeCodeRule;
import com.aegisx.policy.rules.RuleContext;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleEngine integration")
class RuleEngineTest {

    private final RuleEngine ruleEngine = new RuleEngine(
            new DocumentCompletenessRule(),
            new BeneficiaryMatchRule(),
            new AmountConsistencyRule(),
            new CurrencyConsistencyRule(),
            new PurposeCodeRule());

    private RuleContext passingContext;

    @BeforeEach
    void setUp() {
        Transaction transaction = Transaction.builder()
                .referenceNumber("TXN-20260922-ABC123")
                .beneficiaryName("XYZ Imports")
                .amount(new BigDecimal("50000.00"))
                .currency("USD")
                .purposeCode("P0101")
                .status(TransactionStatus.POLICY_EVALUATION)
                .build();

        DocumentExtraction extraction = DocumentExtraction.builder()
                .extractedData(new ObjectMapper().createObjectNode()
                        .put("beneficiary", "XYZ Imports")
                        .put("amount", "50000.00")
                        .put("currency", "USD"))
                .confidenceScore(95.0)
                .build();

        passingContext = RuleContext.builder()
                .transaction(transaction)
                .requiredDocuments(List.of(
                        requiredDocument(DocumentType.INVOICE, true),
                        requiredDocument(DocumentType.SHIPPING_PROOF, true),
                        requiredDocument(DocumentType.EXPORT_DECLARATION, true)))
                .documentExtraction(extraction)
                .build();
    }

    @Test
    @DisplayName("all real policy rules approve consistent complete evidence")
    void testAllRulesPass() {
        PolicyEngineResult result = ruleEngine.evaluate(passingContext);

        assertEquals("APPROVED", result.getFinalDecision());
        assertEquals(5, result.getRuleResults().size());
        assertTrue(result.getRuleResults().stream().allMatch(rule -> rule.getPassed()));
    }

    @Test
    @DisplayName("low heuristic OCR confidence does not gate matching structured values")
    void heuristicOcrConfidenceDoesNotGatePolicy() {
        var extractionWithoutScore = DocumentExtraction.builder()
                .extractedData(passingContext.getDocumentExtraction().getExtractedData())
                .confidenceScore(0.0)
                .build();
        RuleContext context = RuleContext.builder()
                .transaction(passingContext.getTransaction())
                .requiredDocuments(passingContext.getRequiredDocuments())
                .documentExtraction(extractionWithoutScore)
                .build();

        PolicyEngineResult result = ruleEngine.evaluate(context);

        assertEquals("APPROVED", result.getFinalDecision());
        assertEquals(5, result.getRuleResults().size());
        assertTrue(result.getRuleResults().stream().noneMatch(rule ->
                rule.getRuleName().equals("OCR_CONFIDENCE") || rule.getRuleName().equals("AI_CONFIDENCE")));
    }

    @Test
    @DisplayName("repeated evaluations of identical rule inputs return identical rule results")
    void repeatedEvaluationsAreDeterministic() {
        var expected = ruleEngine.evaluate(passingContext).getRuleResults();

        for (int attempt = 0; attempt < 20; attempt++) {
            assertEquals(expected, ruleEngine.evaluate(passingContext).getRuleResults());
        }
    }

    @Test
    @DisplayName("a missing required document fails closed")
    void testSingleRuleFails() {
        RuleContext incomplete = RuleContext.builder()
                .transaction(passingContext.getTransaction())
                .requiredDocuments(List.of(
                        requiredDocument(DocumentType.INVOICE, true),
                        requiredDocument(DocumentType.SHIPPING_PROOF, false),
                        requiredDocument(DocumentType.EXPORT_DECLARATION, true)))
                .documentExtraction(passingContext.getDocumentExtraction())
                .build();

        PolicyEngineResult result = ruleEngine.evaluate(incomplete);

        assertEquals("COMPLIANCE_HOLD", result.getFinalDecision());
        assertTrue(result.getRuleResults().stream().anyMatch(rule -> !rule.getPassed()));
    }

    @Test
    @DisplayName("real rule results include an auditable name and reason")
    void testRuleResultsAuditable() {
        PolicyEngineResult result = ruleEngine.evaluate(passingContext);

        result.getRuleResults().forEach(rule -> {
            assertNotNull(rule.getRuleName());
            assertFalse(rule.getRuleName().isBlank());
            assertNotNull(rule.getReason());
            assertFalse(rule.getReason().isBlank());
        });
    }

    @Test
    @DisplayName("evaluation records a non-negative processing duration")
    void testProcessingDurationRecorded() {
        PolicyEngineResult result = ruleEngine.evaluate(passingContext);

        assertTrue(result.getProcessingDurationMs() >= 0);
    }

    private static RequiredDocument requiredDocument(DocumentType type, boolean fulfilled) {
        return RequiredDocument.builder()
                .documentType(type)
                .fulfilled(fulfilled)
                .build();
    }
}
