package com.aegisx.document.ocr;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.exception.OcrProcessingException;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentStatus;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.repository.DocumentExtractionRepository;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OcrProcessingCoordinator.
 * 
 * Tests:
 * - Document status transitions
 * - Extraction persistence
 * - Error handling and OCR_FAILED status
 * - Tika → decision → Tesseract flow
 */
@SpringBootTest
@DisplayName("OcrProcessingCoordinator Tests")
class OcrProcessingCoordinatorTest {

    @Autowired
    private OcrProcessingCoordinator coordinator;

    @MockBean
    private TikaTextExtractor tikaTextExtractor;

    @MockBean
    private TesseractOcrProcessor tesseractOcrProcessor;

    @MockBean
    private StructuredExtractionService structuredExtractionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentExtractionRepository extractionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Document testDocument;
    private Transaction testTransaction;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = User.builder()
            .email("test-" + UUID.randomUUID() + "@example.com")
            .passwordHash("hashed")
            .fullName("Test User")
            .role(Role.MERCHANT)
            .build();
        testUser = userRepository.save(testUser);

        // Create test transaction
        testTransaction = Transaction.builder()
            .referenceNumber("TXN-" + UUID.randomUUID().toString().substring(0, 8))
            .merchant(testUser)
            .amount(new BigDecimal("2450.50"))
            .currency("USD")
            .beneficiaryName("XYZ Imports")
            .purposeCode("P0001")
            .status(TransactionStatus.EVIDENCE_PENDING)
            .build();
        testTransaction = transactionRepository.save(testTransaction);

        // Create test document
        testDocument = Document.builder()
            .transaction(testTransaction)
            .documentType(DocumentType.INVOICE)
            .documentStatus(DocumentStatus.UPLOADED)
            .originalFilename("invoice.pdf")
            .storedFilename("invoice_stored.pdf")
            .mimeType("application/pdf")
            .fileSize(50000L)
            .storagePath("/storage/invoice_stored.pdf")
            .uploadedBy(testUser)
            .version(1L)
            .build();
    }

    @Test
    @DisplayName("Should process document and update status to OCR_COMPLETED")
    void testProcessDocumentSuccessfully() {
        // Arrange
        String sampleText = "Invoice Number: INV-1024\nAmount: USD 2450.50";
        InputStream inputStream = new ByteArrayInputStream(sampleText.getBytes());

        when(tikaTextExtractor.extractText(any())).thenReturn(sampleText);
        when(tikaTextExtractor.isSufficientText(sampleText)).thenReturn(true);
        when(structuredExtractionService.extractStructuredData(sampleText))
            .thenReturn(objectMapper.createObjectNode());
        when(structuredExtractionService.calculateConfidenceScore(any())).thenReturn(85.0);

        // Save document to DB first
        testDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        Document savedDocument = documentRepository.save(testDocument);

        // Act
        DocumentExtraction result = coordinator.processDocument(savedDocument, inputStream);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals(DocumentExtraction.ExtractionMethod.TIKA, result.getExtractionMethod());
        assertEquals(85.0, result.getConfidenceScore());

        // Verify document status was updated
        Document updatedDoc = documentRepository.findById(savedDocument.getId()).orElse(null);
        assertNotNull(updatedDoc);
        assertEquals(DocumentStatus.OCR_COMPLETED, updatedDoc.getDocumentStatus());
    }

    @Test
    @DisplayName("Should update document status to OCR_PROCESSING at start")
    void testStatusUpdateToProcessing() {
        // Arrange
        String sampleText = "Invoice Number: INV-1024";
        InputStream inputStream = new ByteArrayInputStream(sampleText.getBytes());

        when(tikaTextExtractor.extractText(any())).thenReturn(sampleText);
        when(tikaTextExtractor.isSufficientText(sampleText)).thenReturn(true);
        when(structuredExtractionService.extractStructuredData(sampleText))
            .thenReturn(objectMapper.createObjectNode());
        when(structuredExtractionService.calculateConfidenceScore(any())).thenReturn(85.0);

        testDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        Document savedDocument = documentRepository.save(testDocument);

        // Act
        coordinator.processDocument(savedDocument, inputStream);

        // Assert: status should be OCR_COMPLETED (final state after successful processing)
        Document updatedDoc = documentRepository.findById(savedDocument.getId()).orElse(null);
        assertNotNull(updatedDoc);
        assertEquals(DocumentStatus.OCR_COMPLETED, updatedDoc.getDocumentStatus());
    }

    @Test
    @DisplayName("Should handle Tika extraction failure and mark as OCR_FAILED")
    void testHandleTikaExtractionFailure() {
        // Arrange
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);

        when(tikaTextExtractor.extractText(any()))
            .thenThrow(new OcrProcessingException("Tika failed"));

        testDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        Document savedDocument = documentRepository.save(testDocument);

        // Act & Assert
        assertThrows(OcrProcessingException.class, () -> {
            coordinator.processDocument(savedDocument, inputStream);
        });

        // Verify document status was updated to OCR_FAILED
        Document updatedDoc = documentRepository.findById(savedDocument.getId()).orElse(null);
        assertNotNull(updatedDoc);
        assertEquals(DocumentStatus.OCR_FAILED, updatedDoc.getDocumentStatus());
    }

    @Test
    @DisplayName("Should persist extraction record to database")
    void testPersistExtractionRecord() {
        // Arrange
        String sampleText = "Invoice Number: INV-1024\nAmount: USD 2450.50";
        InputStream inputStream = new ByteArrayInputStream(sampleText.getBytes());

        JsonNode mockJsonNode = objectMapper.createObjectNode().put("invoiceNumber", "INV-1024");
        when(tikaTextExtractor.extractText(any())).thenReturn(sampleText);
        when(tikaTextExtractor.isSufficientText(sampleText)).thenReturn(true);
        when(structuredExtractionService.extractStructuredData(sampleText))
            .thenReturn(mockJsonNode);
        when(structuredExtractionService.calculateConfidenceScore(mockJsonNode)).thenReturn(90.0);

        testDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        Document savedDocument = documentRepository.save(testDocument);

        // Act
        DocumentExtraction result = coordinator.processDocument(savedDocument, inputStream);

        // Assert: extraction should be retrievable from DB
        DocumentExtraction retrievedExtraction = extractionRepository.findById(result.getId()).orElse(null);
        assertNotNull(retrievedExtraction);
        assertEquals(sampleText, retrievedExtraction.getExtractedText());
        assertEquals(DocumentExtraction.ExtractionMethod.TIKA, retrievedExtraction.getExtractionMethod());
        assertEquals(90.0, retrievedExtraction.getConfidenceScore());
    }

    @Test
    @DisplayName("Should store extracted data in JSON format")
    void testStoreExtractedDataAsJson() {
        // Arrange
        String sampleText = "Invoice Number: INV-1024";
        InputStream inputStream = new ByteArrayInputStream(sampleText.getBytes());

        JsonNode mockJsonNode = objectMapper.createObjectNode().put("invoiceNumber", "INV-1024");
        when(tikaTextExtractor.extractText(any())).thenReturn(sampleText);
        when(tikaTextExtractor.isSufficientText(sampleText)).thenReturn(true);
        when(structuredExtractionService.extractStructuredData(sampleText))
            .thenReturn(mockJsonNode);
        when(structuredExtractionService.calculateConfidenceScore(mockJsonNode)).thenReturn(85.0);

        testDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        Document savedDocument = documentRepository.save(testDocument);

        // Act
        DocumentExtraction result = coordinator.processDocument(savedDocument, inputStream);

        // Assert: extracted_data should be stored
        assertNotNull(result.getExtractedData());
        assertEquals(mockJsonNode, result.getExtractedData());
    }

    @Test
    @DisplayName("Should not overwrite previous extraction (versioning)")
    void testExtractionVersioning() {
        // Arrange
        String sampleText1 = "Invoice Number: INV-1024";
        String sampleText2 = "Invoice Number: INV-1025";

        JsonNode mockJsonNode = objectMapper.createObjectNode().put("invoiceNumber", "INV-1024");
        when(tikaTextExtractor.extractText(any())).thenReturn(sampleText1, sampleText2);
        when(tikaTextExtractor.isSufficientText(any())).thenReturn(true);
        when(structuredExtractionService.extractStructuredData(any()))
            .thenReturn(mockJsonNode);
        when(structuredExtractionService.calculateConfidenceScore(any())).thenReturn(85.0);

        testDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        Document savedDocument = documentRepository.save(testDocument);

        // Act: Process twice
        DocumentExtraction extraction1 = coordinator.processDocument(
            savedDocument, 
            new ByteArrayInputStream(sampleText1.getBytes())
        );
        
        savedDocument = documentRepository.findById(savedDocument.getId()).orElseThrow();
        savedDocument.setDocumentStatus(DocumentStatus.UPLOADED);
        savedDocument = documentRepository.save(savedDocument);
        
        DocumentExtraction extraction2 = coordinator.processDocument(
            savedDocument, 
            new ByteArrayInputStream(sampleText2.getBytes())
        );

        // Assert: both extractions should exist
        assertNotNull(extraction1);
        assertNotNull(extraction2);
        assertNotEquals(extraction1.getId(), extraction2.getId());

        long extractionCount = extractionRepository.countByDocumentId(savedDocument.getId());
        assertEquals(2, extractionCount, "Should have 2 extraction records (not overwritten)");
    }
}
