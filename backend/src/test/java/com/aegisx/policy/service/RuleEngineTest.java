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
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleEngine Tests")
@ExtendWith(MockitoExtension.class)
class RuleEngineTest {
    
    @InjectMocks
    private RuleEngine ruleEngine;
    
    private Transaction transaction;
    private RuleContext.RuleContextBuilder contextBuilder;
    
    @BeforeEach
    void setUp() {
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
