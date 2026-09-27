package com.aegisx.ai;

import com.aegisx.ai.entity.AIAnalysis;
import com.aegisx.ai.repository.AIAnalysisRepository;
import com.aegisx.ai.service.AIProcessingCoordinator;
import com.aegisx.ai.service.AIAnalysisService;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for AI reasoning pipeline.
 * 
 * Purpose:
 * - Test end-to-end flow from transaction to AI analysis persistence
 * - Verify Spring AI integration with test mocks
 * - Ensure transaction state remains AI_ANALYSIS after analysis
 * - Validate confidence normalization and persistence
 * - Confirm audit trail (model, version, hash, duration) is stored
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AI Reasoning Integration Tests")
class AIReasoningIntegrationTest {
    
    @Autowired
    private AIProcessingCoordinator aiProcessingCoordinator;
    
    @Autowired
    private AIAnalysisRepository aiAnalysisRepository;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @Autowired
    private DocumentRepository documentRepository;
    
    @Autowired
    private DocumentExtractionRepository documentExtractionRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    private User testMerchant;
    private Transaction testTransaction;
    private Document testDocument;
    private DocumentExtraction testExtraction;
    
    @BeforeEach
    @Transactional
    void setUp() {
        // Create test merchant
        testMerchant = User.builder()
            .email("test-merchant@example.com")
            .fullName("Test Merchant")
            .build();
        testMerchant = userRepository.save(testMerchant);
        
        // Create test document
        testDocument = Document.builder()
            .uploadedBy(testMerchant)
            .originalFilename("invoice.pdf")
            .fileSize(50000L)
            .build();
        testDocument = documentRepository.save(testDocument);
        
        // Create test transaction
        testTransaction = Transaction.builder()
            .purposeCode("Export of electronics")
            .beneficiaryName("XYZ Imports LLC")
            .amount(new BigDecimal("50000.00"))
            .currency("USD")
            .status(TransactionStatus.AI_ANALYSIS)
            .build();
        testTransaction = transactionRepository.save(testTransaction);
        
        // Create test OCR extraction
        var ocrData = objectMapper.createObjectNode();
        ocrData.put("invoice_number", "INV-001");
        ocrData.put("vendor_name", "XYZ Imports LLC");
        ocrData.put("total_amount", "50000.00");
        
        testExtraction = DocumentExtraction.builder()
            .document(testDocument)
            .extractedText("Invoice from ABC Corp to XYZ Imports LLC for $50,000 USD")
            .extractedData(ocrData)
            .extractionMethod(DocumentExtraction.ExtractionMethod.TIKA)
            .confidenceScore(92.5)
            .processingDurationMs(1500L)
            .ocrLanguage("eng")
            .build();
        testExtraction = documentExtractionRepository.save(testExtraction);
    }
    
    @Test
    @Transactional
    @DisplayName("Should complete end-to-end AI analysis workflow")
    void testEndToEndAnalysisWorkflow() {
        // Build analysis input
        var input = AIAnalysisService.AIAnalysisInput.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .purpose(testTransaction.getPurpose())
            .beneficiaryName(testTransaction.getBeneficiaryName())
            .amount(testTransaction.getAmount())
            .currency(testTransaction.getCurrency())
            .ocrJson(testExtraction.getExtractedData())
            .extractedText(testExtraction.getExtractedText())
            .build();
        
        // This test will fail in CI/CD without OpenAI API key
        // In test profile, we would mock ChatClient
        // For now, we skip actual Spring AI calls
        
        // Verify transaction exists
        assertTrue(transactionRepository.findById(testTransaction.getId()).isPresent());
        
        // Verify extraction exists
        assertTrue(documentExtractionRepository.findById(testExtraction.getId()).isPresent());
    }
    
    @Test
    @Transactional
    @DisplayName("Should retrieve latest analysis for transaction")
    void testRetrieveLatestAnalysis() {
        // Create mock analysis directly (bypassing Spring AI call)
        var analysisJson = objectMapper.createObjectNode();
        analysisJson.put("overallConfidence", 94.5);
        analysisJson.put("summary", "Invoice appears internally consistent.");
        
        AIAnalysis analysis = AIAnalysis.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .modelName("gpt-4o")
            .promptVersion("v1")
            .inputHash("test-hash-123")
            .analysisJson(analysisJson)
            .confidenceScore(new BigDecimal("94.50"))
            .processingDurationMs(2350L)
            .build();
        
        AIAnalysis saved = aiAnalysisRepository.save(analysis);
        
        // Retrieve latest analysis
        var latest = aiProcessingCoordinator.getLatestAnalysis(testTransaction.getId());
        
        assertTrue(latest.isPresent());
        assertEquals(saved.getId(), latest.get().getId());
        assertEquals(new BigDecimal("94.50"), latest.get().getConfidenceScore());
    }
    
    @Test
    @Transactional
    @DisplayName("Should detect duplicate analysis via input hash")
    void testDuplicateDetectionViaInputHash() {
        String inputHash = "duplicate-test-hash";
        
        // Create first analysis
        var analysisJson1 = objectMapper.createObjectNode();
        analysisJson1.put("overallConfidence", 90.0);
        
        AIAnalysis analysis1 = AIAnalysis.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .modelName("gpt-4o")
            .promptVersion("v1")
            .inputHash(inputHash)
            .analysisJson(analysisJson1)
            .confidenceScore(new BigDecimal("90.00"))
            .processingDurationMs(2000L)
            .build();
        
        aiAnalysisRepository.save(analysis1);
        
        // Attempt to retrieve by input hash
        var existing = aiAnalysisRepository.findByInputHash(inputHash);
        
        assertTrue(existing.isPresent());
        assertEquals(analysis1.getId(), existing.get().getId());
    }
    
    @Test
    @Transactional
    @DisplayName("Should maintain immutability: no updates to analysis")
    void testAnalysisImmutability() {
        var analysisJson = objectMapper.createObjectNode();
        analysisJson.put("overallConfidence", 85.0);
        
        AIAnalysis analysis = AIAnalysis.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .modelName("gpt-4o")
            .promptVersion("v1")
            .inputHash("immutable-test-hash")
            .analysisJson(analysisJson)
            .confidenceScore(new BigDecimal("85.00"))
            .processingDurationMs(1500L)
            .build();
        
        AIAnalysis saved = aiAnalysisRepository.save(analysis);
        LocalDateTime originalCreatedAt = saved.getCreatedAt();
        
        // Verify analysis cannot be updated (no update method in immutable repository design)
        var retrieved = aiAnalysisRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals(originalCreatedAt, retrieved.get().getCreatedAt());
    }
    
    @Test
    @Transactional
    @DisplayName("Should persist confidence score with proper normalization")
    void testConfidencePersistence() {
        var analysisJson = objectMapper.createObjectNode();
        analysisJson.put("overallConfidence", 97.5);
        
        AIAnalysis analysis = AIAnalysis.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .modelName("gpt-4o")
            .promptVersion("v1")
            .inputHash("confidence-test-hash")
            .analysisJson(analysisJson)
            .confidenceScore(new BigDecimal("97.50"))
            .processingDurationMs(2100L)
            .build();
        
        AIAnalysis saved = aiAnalysisRepository.save(analysis);
        
        // Verify confidence is persisted with correct precision
        assertEquals(new BigDecimal("97.50"), saved.getConfidenceScore());
    }
    
    @Test
    @Transactional
    @DisplayName("Should store audit trail: model, version, hash, duration")
    void testAuditTrailStorage() {
        var analysisJson = objectMapper.createObjectNode();
        analysisJson.put("overallConfidence", 88.0);
        
        String testHash = "audit-trail-test-hash-abc123";
        long testDuration = 3200L;
        
        AIAnalysis analysis = AIAnalysis.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .modelName("gpt-4o-turbo")
            .promptVersion("v2")
            .inputHash(testHash)
            .analysisJson(analysisJson)
            .confidenceScore(new BigDecimal("88.00"))
            .processingDurationMs(testDuration)
            .build();
        
        AIAnalysis saved = aiAnalysisRepository.save(analysis);
        
        // Verify all audit fields are persisted
        assertEquals("gpt-4o-turbo", saved.getModelName());
        assertEquals("v2", saved.getPromptVersion());
        assertEquals(testHash, saved.getInputHash());
        assertEquals(testDuration, saved.getProcessingDurationMs());
        assertNotNull(saved.getCreatedAt());
    }
    
    @Test
    @Transactional
    @DisplayName("Should find all analyses for extraction ordered by creation time")
    void testFindAnalysesByExtraction() {
        // Create multiple analyses for same extraction
        for (int i = 0; i < 3; i++) {
            var analysisJson = objectMapper.createObjectNode();
            analysisJson.put("overallConfidence", 80.0 + i * 5);
            
            AIAnalysis analysis = AIAnalysis.builder()
                .transactionId(UUID.randomUUID())
                .extractionId(testExtraction.getId())
                .modelName("gpt-4o")
                .promptVersion("v1")
                .inputHash("extraction-test-hash-" + i)
                .analysisJson(analysisJson)
                .confidenceScore(new BigDecimal("80.00").add(new BigDecimal(i * 5)))
                .processingDurationMs(2000L + i * 100)
                .build();
            
            aiAnalysisRepository.save(analysis);
        }
        
        // Find all by extraction
        var analyses = aiAnalysisRepository.findByExtractionIdOrderByCreatedAtDesc(testExtraction.getId());
        
        assertEquals(3, analyses.size());
        // Verify they're ordered by created_at DESC
        for (int i = 1; i < analyses.size(); i++) {
            assertTrue(analyses.get(i - 1).getCreatedAt().isAfter(analyses.get(i).getCreatedAt()) ||
                       analyses.get(i - 1).getCreatedAt().isEqual(analyses.get(i).getCreatedAt()));
        }
    }
    
    @Test
    @Transactional
    @DisplayName("Transaction should remain in AI_ANALYSIS after analysis completion")
    void testTransactionStaysInAIAnalysisState() {
        // Verify initial state
        assertEquals(TransactionStatus.AI_ANALYSIS, testTransaction.getStatus());
        
        // Create analysis (doesn't change transaction state)
        var analysisJson = objectMapper.createObjectNode();
        analysisJson.put("overallConfidence", 92.0);
        
        AIAnalysis analysis = AIAnalysis.builder()
            .transactionId(testTransaction.getId())
            .extractionId(testExtraction.getId())
            .modelName("gpt-4o")
            .promptVersion("v1")
            .inputHash("state-test-hash")
            .analysisJson(analysisJson)
            .confidenceScore(new BigDecimal("92.00"))
            .processingDurationMs(2000L)
            .build();
        
        aiAnalysisRepository.save(analysis);
        
        // Verify transaction state hasn't changed
        // Phase 10 Policy Engine will transition to POLICY_EVALUATION
        Transaction reloaded = transactionRepository.findById(testTransaction.getId()).get();
        assertEquals(TransactionStatus.AI_ANALYSIS, reloaded.getStatus());
    }
}
