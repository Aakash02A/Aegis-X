package com.aegisx.policy.service;

import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.common.exception.MissingEvidenceException;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.policy.entity.PolicyEvaluation;
import com.aegisx.policy.repository.PolicyEvaluationRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("PolicyEvaluationService Tests")
@ExtendWith(MockitoExtension.class)
class PolicyEvaluationServiceTest {
    
    @Mock
    private RuleEngine ruleEngine;
    
    @Mock
    private PolicyEvaluationRepository policyEvaluationRepository;
    
    @Mock
    private TransactionRepository transactionRepository;
    
    @Mock
    private DocumentExtractionRepository documentExtractionRepository;
    
    @Mock
    private RequiredDocumentRepository requiredDocumentRepository;
    
    @Mock
    private AIAnalysisRepository aiAnalysisRepository;
    
    @Mock
    private TransactionStateMachine transactionStateMachine;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @InjectMocks
    private PolicyEvaluationService policyEvaluationService;
    
    private UUID transactionId;
    private Transaction transaction;
    private PolicyEngineResult approvedResult;
    
    @BeforeEach
    void setUp() {
        transactionId = UUID.randomUUID();
        transaction = Transaction.builder()
            .referenceNumber("TXN-20260922-ABC123")
            .beneficiaryName("XYZ Imports")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .purposeCode("P0101")
            .status(TransactionStatus.AI_ANALYSIS)
            .build();
        
        approvedResult = new PolicyEngineResult();
        approvedResult.setFinalDecision("APPROVED");
        approvedResult.setProcessingDurationMs(150L);
    }
    
    @Test
    @DisplayName("Should transition to APPROVED when policy engine approves")
    void testEvaluateAndTransitionToApproved() {
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));
        when(ruleEngine.evaluate(any())).thenReturn(approvedResult);
        when(policyEvaluationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenReturn(transaction);
        
        PolicyEvaluation result = policyEvaluationService.evaluateTransaction(transactionId);
        
        assertNotNull(result);
        assertEquals("APPROVED", result.getFinalDecision());
        

    }
    
    @Test
    @DisplayName("Should transition to COMPLIANCE_HOLD when policy engine denies")
    void testEvaluateAndTransitionToComplianceHold() {
        PolicyEngineResult holdResult = new PolicyEngineResult();
        holdResult.setFinalDecision("COMPLIANCE_HOLD");
        holdResult.setProcessingDurationMs(200L);
        
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));
        when(ruleEngine.evaluate(any())).thenReturn(holdResult);
        when(policyEvaluationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenReturn(transaction);
        
        PolicyEvaluation result = policyEvaluationService.evaluateTransaction(transactionId);
        
        assertNotNull(result);
        assertEquals("COMPLIANCE_HOLD", result.getFinalDecision());
        

    }
    
    @Test
    @DisplayName("Should throw exception when transaction not found")
    void testThrowWhenTransactionNotFound() {
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.empty());
        
        assertThrows(MissingEvidenceException.class, 
            () -> policyEvaluationService.evaluateTransaction(transactionId));
    }
    
    @Test
    @DisplayName("Should persist PolicyEvaluation with decision and duration")
    void testPersistPolicyEvaluation() {
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));
        when(ruleEngine.evaluate(any())).thenReturn(approvedResult);
        when(policyEvaluationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenReturn(transaction);
        
        policyEvaluationService.evaluateTransaction(transactionId);
        
        ArgumentCaptor<PolicyEvaluation> evaluationCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evaluationCaptor.capture());
        
        PolicyEvaluation saved = evaluationCaptor.getValue();
        assertNotNull(saved);
        assertEquals("APPROVED", saved.getFinalDecision());
        assertEquals(150L, saved.getProcessingDurationMs());
    }
}
