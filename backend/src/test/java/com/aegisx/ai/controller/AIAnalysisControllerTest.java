package com.aegisx.ai.controller;

import com.aegisx.ai.service.AIProcessingCoordinator;
import com.aegisx.common.exception.AIAnalysisException;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AIAnalysisControllerTest {

    @Test
    void aiFailureIsRecordedWithoutReturningProviderDetails() {
        AIProcessingCoordinator coordinator = mock(AIProcessingCoordinator.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        DocumentExtractionRepository extractionRepository = mock(DocumentExtractionRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        TransactionService transactionService = mock(TransactionService.class);
        var controller = new AIAnalysisController(coordinator, mock(com.aegisx.ai.repository.AIAnalysisRepository.class),
                transactionRepository, extractionRepository, documentRepository, new ObjectMapper(), transactionService);

        UUID transactionId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        Transaction transaction = new Transaction();
        transaction.setId(transactionId);
        transaction.applyStatus(TransactionStatus.AI_ANALYSIS);
        transaction.setPurposeCode("P0101");
        transaction.setBeneficiaryName("XYZ Imports");
        transaction.setAmount(new BigDecimal("2500.00"));
        transaction.setCurrency("USD");
        DocumentExtraction extraction = new DocumentExtraction();
        extraction.setId(extractionId);
        extraction.setExtractedData(new ObjectMapper().createObjectNode());
        extraction.setExtractedText("invoice text");

        var authentication = new TestingAuthenticationToken("merchant@example.test", "", "ROLE_MERCHANT");
        when(transactionService.getAuthorizedTransaction(transactionId, authentication)).thenReturn(transaction);
        when(extractionRepository.findLatestByTransactionId(transactionId)).thenReturn(Optional.of(extraction));
        when(documentRepository.findByTransactionIdOrderByCreatedAtDesc(transactionId)).thenReturn(List.of(new Document()));
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));
        when(coordinator.triggerAnalysis(any())).thenThrow(new AIAnalysisException("provider detail that must not be exposed"));

        var response = controller.triggerAnalysis(transactionId, authentication);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) response.getBody();
        assertEquals("AI Analysis Failed", body.get("error"));
        assertFalse(body.get("message").toString().contains("provider detail"));
        verify(transactionService).transitionStatus(transactionId, TransactionStatus.AI_ANALYSIS_FAILED,
                "AI_SERVICE", "AI advisory analysis failed");
    }
}
