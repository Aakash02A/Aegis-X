package com.aegisx.policy.rules;

import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PurposeCodeRule Tests")
class PurposeCodeRuleTest {
    
    private PurposeCodeRule rule;
    private RuleContext.RuleContextBuilder contextBuilder;
    
    @BeforeEach
    void setUp() {
        rule = new PurposeCodeRule();
        
        contextBuilder = RuleContext.builder();
    }
    
    @Test
    @DisplayName("Should pass for valid purpose code P0101")
    void testPassForValidCodeP0101() {
        Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P0101")
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        RuleContext context = contextBuilder.transaction(transaction).build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertTrue(result.getPassed());
        assertEquals("PURPOSE_CODE", result.getRuleName());
    }
    
    @Test
    @DisplayName("Should pass for valid purpose code P0202")
    void testPassForValidCodeP0202() {
        Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P0202")
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        RuleContext context = contextBuilder.transaction(transaction).build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertTrue(result.getPassed());
    }
    
    @Test
    @DisplayName("Should pass for valid purpose code P0301")
    void testPassForValidCodeP0301() {
        Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P0301")
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        RuleContext context = contextBuilder.transaction(transaction).build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertTrue(result.getPassed());
    }
    
    @Test
    @DisplayName("Should fail for invalid purpose code")
    void testFailForInvalidCode() {
        Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P9999")  // Invalid code
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        RuleContext context = contextBuilder.transaction(transaction).build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertFalse(result.getPassed());
        assertNotNull(result.getReason());
    }
    
    @Test
    @DisplayName("Should fail for null purpose code")
    void testFailForNullCode() {
        Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode(null)
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        RuleContext context = contextBuilder.transaction(transaction).build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertFalse(result.getPassed());
    }
    
    @Test
    @DisplayName("Should be case-insensitive for purpose code")
    void testCaseInsensitive() {
        Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("p0101")  // lowercase
            .status(TransactionStatus.POLICY_EVALUATION)
            .build();
        
        RuleContext context = contextBuilder.transaction(transaction).build();
        
        RuleEvaluationResult result = rule.evaluate(context);
        
        assertTrue(result.getPassed());
    }
}
