package com.aegisx.policy.service;

import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.policy.rules.RuleContext;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleEngine Tests")
@ExtendWith(MockitoExtension.class)
class RuleEngineTest {

    @Mock private com.aegisx.policy.rules.DocumentCompletenessRule documentCompletenessRule;
    @Mock private com.aegisx.policy.rules.BeneficiaryMatchRule beneficiaryMatchRule;
    @Mock private com.aegisx.policy.rules.AmountConsistencyRule amountConsistencyRule;
    @Mock private com.aegisx.policy.rules.CurrencyConsistencyRule currencyConsistencyRule;
    @Mock private com.aegisx.policy.rules.PurposeCodeRule purposeCodeRule;
    @Mock private com.aegisx.policy.rules.OcrConfidenceRule ocrConfidenceRule;
    @Mock private com.aegisx.policy.rules.AiConfidenceRule aiConfidenceRule;
    
    @InjectMocks
    private RuleEngine ruleEngine;
    
    private Transaction transaction;
    private RuleContext.RuleContextBuilder contextBuilder;
    
    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(documentCompletenessRule.evaluate(any())).thenReturn(ruleResult("document completeness", true));
        org.mockito.Mockito.lenient().when(beneficiaryMatchRule.evaluate(any())).thenReturn(ruleResult("beneficiary match", true));
        org.mockito.Mockito.lenient().when(amountConsistencyRule.evaluate(any())).thenReturn(ruleResult("amount consistency", true));
        org.mockito.Mockito.lenient().when(currencyConsistencyRule.evaluate(any())).thenReturn(ruleResult("currency consistency", true));
        org.mockito.Mockito.lenient().when(purposeCodeRule.evaluate(any())).thenReturn(ruleResult("purpose code", true));
        org.mockito.Mockito.lenient().when(ocrConfidenceRule.evaluate(any())).thenReturn(ruleResult("OCR confidence", true));
        org.mockito.Mockito.lenient().when(aiConfidenceRule.evaluate(any())).thenReturn(ruleResult("AI confidence", true));
        transaction = Transaction.builder()
            .referenceNumber("TXN-20260922-ABC123")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P0101")
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        contextBuilder = RuleContext.builder()
            .transaction(transaction);
    }
    
    @Test
    @DisplayName("Should return APPROVED when all rules pass")
    void testAllRulesPass() {
        RequiredDocument invoice = RequiredDocument.builder()
            .documentType(DocumentType.INVOICE)
            .fulfilled(true)
            .build();
        
        RequiredDocument shipping = RequiredDocument.builder()
            .documentType(DocumentType.SHIPPING_PROOF)
            .fulfilled(true)
            .build();
        
        RequiredDocument export = RequiredDocument.builder()
            .documentType(DocumentType.EXPORT_DECLARATION)
            .fulfilled(true)
            .build();
        
        DocumentExtraction extraction = DocumentExtraction.builder()
            .build();
        
        AIAnalysis analysis = AIAnalysis.builder()
            .confidenceScore(new BigDecimal("88.0"))
            .build();
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(invoice, shipping, export))
            .documentExtraction(extraction)
            .aiAnalysis(analysis)
            .build();
        
        PolicyEngineResult result = ruleEngine.evaluate(context);
        
        assertEquals("APPROVED", result.getFinalDecision());
        assertTrue(result.getRuleResults().stream().allMatch(r -> r.getPassed()));
        assertEquals(7, result.getRuleResults().size());
        assertTrue(result.getProcessingDurationMs() >= 0);
    }
    
    @Test
    @DisplayName("Should return COMPLIANCE_HOLD when any rule fails")
    void testSingleRuleFails() {
        when(documentCompletenessRule.evaluate(any())).thenReturn(ruleResult("document completeness", false));
        RequiredDocument invoice = RequiredDocument.builder()
            .documentType(DocumentType.INVOICE)
            .fulfilled(true)
            .build();
        
        RequiredDocument shipping = RequiredDocument.builder()
            .documentType(DocumentType.SHIPPING_PROOF)
            .fulfilled(false)  // FAIL: Not fulfilled
            .build();
        
        RequiredDocument export = RequiredDocument.builder()
            .documentType(DocumentType.EXPORT_DECLARATION)
            .fulfilled(true)
            .build();
        
        DocumentExtraction extraction = DocumentExtraction.builder()
            .build();
        
        AIAnalysis analysis = AIAnalysis.builder()
            .confidenceScore(new BigDecimal("88.0"))
            .build();
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(invoice, shipping, export))
            .documentExtraction(extraction)
            .aiAnalysis(analysis)
            .build();
        
        PolicyEngineResult result = ruleEngine.evaluate(context);
        
        assertEquals("COMPLIANCE_HOLD", result.getFinalDecision());
        assertTrue(result.getRuleResults().stream().anyMatch(r -> !r.getPassed()));
    }

    private com.aegisx.policy.rules.RuleEvaluationResult ruleResult(String name, boolean passed) {
        return com.aegisx.policy.rules.RuleEvaluationResult.builder()
                .ruleName(name)
                .passed(passed)
                .reason(passed ? "Passed" : "Failed")
                .evidence("test fixture")
                .build();
    }
    
    @Test
    @DisplayName("Should record all rule results with names and reasons")
    void testRuleResultsAuditable() {
        RequiredDocument invoice = RequiredDocument.builder()
            .documentType(DocumentType.INVOICE)
            .fulfilled(true)
            .build();
        
        RequiredDocument shipping = RequiredDocument.builder()
            .documentType(DocumentType.SHIPPING_PROOF)
            .fulfilled(true)
            .build();
        
        RequiredDocument export = RequiredDocument.builder()
            .documentType(DocumentType.EXPORT_DECLARATION)
            .fulfilled(true)
            .build();
        
        DocumentExtraction extraction = DocumentExtraction.builder()
            .build();
        
        AIAnalysis analysis = AIAnalysis.builder()
            .confidenceScore(new BigDecimal("88.0"))
            .build();
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(invoice, shipping, export))
            .documentExtraction(extraction)
            .aiAnalysis(analysis)
            .build();
        
        PolicyEngineResult result = ruleEngine.evaluate(context);
        
        // Verify all rules have names and reasons
        result.getRuleResults().forEach(rule -> {
            assertNotNull(rule.getRuleName());
            assertFalse(rule.getRuleName().isEmpty());
            assertNotNull(rule.getReason());
            assertFalse(rule.getReason().isEmpty());
        });
    }
    
    @Test
    @DisplayName("Should measure processing duration")
    void testProcessingDurationRecorded() {
        RequiredDocument invoice = RequiredDocument.builder()
            .documentType(DocumentType.INVOICE)
            .fulfilled(true)
            .build();
        
        RequiredDocument shipping = RequiredDocument.builder()
            .documentType(DocumentType.SHIPPING_PROOF)
            .fulfilled(true)
            .build();
        
        RequiredDocument export = RequiredDocument.builder()
            .documentType(DocumentType.EXPORT_DECLARATION)
            .fulfilled(true)
            .build();
        
        DocumentExtraction extraction = DocumentExtraction.builder()
            .build();
        
        AIAnalysis analysis = AIAnalysis.builder()
            .confidenceScore(new BigDecimal("88.0"))
            .build();
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(invoice, shipping, export))
            .documentExtraction(extraction)
            .aiAnalysis(analysis)
            .build();
        
        PolicyEngineResult result = ruleEngine.evaluate(context);
        
        // Processing should complete in reasonable time (< 500ms for MVP)
        assertTrue(result.getProcessingDurationMs() >= 0);
        assertTrue(result.getProcessingDurationMs() < 500);
    }
}
