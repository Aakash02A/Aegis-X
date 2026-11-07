package com.aegisx.policy.rules;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DocumentCompletenessRule Tests")
class DocumentCompletenessRuleTest {
    
    private DocumentCompletenessRule rule;
    private RuleContext.RuleContextBuilder contextBuilder;
    
    @BeforeEach
    void setUp() {
        rule = new DocumentCompletenessRule();
        
        Transaction transaction = Transaction.builder()
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P0101")
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        contextBuilder = RuleContext.builder()
            .transaction(transaction);
    }
    
    @Test
    @DisplayName("Should pass when all required documents are fulfilled")
    void testPassWhenAllDocumentsFulfilled() {
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
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(invoice, shipping, export))
            .build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertTrue(result.getPassed());
        assertEquals("DOCUMENT_COMPLETENESS", result.getRuleName());
        assertNotNull(result.getReason());
    }
    
    @Test
    @DisplayName("Should fail when invoice is missing")
    void testFailWhenInvoiceMissing() {
        RequiredDocument shipping = RequiredDocument.builder()
            .documentType(DocumentType.SHIPPING_PROOF)
            .fulfilled(true)
            .build();
        
        RequiredDocument export = RequiredDocument.builder()
            .documentType(DocumentType.EXPORT_DECLARATION)
            .fulfilled(true)
            .build();
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(shipping, export))
            .build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertFalse(result.getPassed());
        assertEquals("DOCUMENT_COMPLETENESS", result.getRuleName());
    }
    
    @Test
    @DisplayName("Should fail when shipping proof is not fulfilled")
    void testFailWhenShippingNotFulfilled() {
        RequiredDocument invoice = RequiredDocument.builder()
            .documentType(DocumentType.INVOICE)
            .fulfilled(true)
            .build();
        
        RequiredDocument shipping = RequiredDocument.builder()
            .documentType(DocumentType.SHIPPING_PROOF)
            .fulfilled(false)
            .build();
        
        RequiredDocument export = RequiredDocument.builder()
            .documentType(DocumentType.EXPORT_DECLARATION)
            .fulfilled(true)
            .build();
        
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(invoice, shipping, export))
            .build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertFalse(result.getPassed());
    }
    
    @Test
    @DisplayName("Should fail when required documents list is empty")
    void testFailWhenDocumentsEmpty() {
        RuleContext context = contextBuilder
            .requiredDocuments(Collections.emptyList())
            .build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertFalse(result.getPassed());
    }

    @Test
    @DisplayName("multiple missing types are reported in stable required-document order")
    void missingTypesAreReportedInStableOrder() {
        RuleContext context = contextBuilder
            .requiredDocuments(Arrays.asList(
                RequiredDocument.builder().documentType(DocumentType.INVOICE).fulfilled(false).build(),
                RequiredDocument.builder().documentType(DocumentType.SHIPPING_PROOF).fulfilled(false).build(),
                RequiredDocument.builder().documentType(DocumentType.EXPORT_DECLARATION).fulfilled(false).build()))
            .build();

        RuleEvaluationResult result = rule.evaluate(context);

        assertFalse(result.getPassed());
        assertEquals("Missing: INVOICE, SHIPPING_PROOF, EXPORT_DECLARATION", result.getEvidence());
    }
}
