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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("OCR processing coordinator integration")
class OcrProcessingCoordinatorTest {

    @Autowired private OcrProcessingCoordinator coordinator;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentExtractionRepository extractionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;

    private Document document;

    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(User.builder()
                .email("ocr-" + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-this-service")
                .fullName("OCR Integration Test")
                .role(Role.MERCHANT)
                .build());
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-OCR-" + UUID.randomUUID())
                .merchant(user)
                .amount(new BigDecimal("2450.50"))
                .currency("USD")
                .beneficiaryName("XYZ Imports")
                .purposeCode("P0001")
                .status(TransactionStatus.EVIDENCE_PENDING)
                .build());
        document = Document.builder()
                .transaction(transaction)
                .documentType(DocumentType.INVOICE)
                .documentStatus(DocumentStatus.UPLOADED)
                .originalFilename("invoice.pdf")
                .storedFilename("invoice_stored.pdf")
                .mimeType("application/pdf")
                .fileSize(50000L)
                .storagePath("ocr-test/invoice_stored.pdf")
                .uploadedBy(user)
                .version(1L)
                .build();
    }

    @Test
    @DisplayName("extracts embedded PDF text and completes document status using real Tika")
    void testProcessDocumentSuccessfully() throws Exception {
        Document saved = documentRepository.saveAndFlush(document);
        DocumentExtraction result = coordinator.processDocument(saved,
                new ByteArrayInputStream(invoicePdf("INV-1024")));

        assertThat(result.getId()).isNotNull();
        assertThat(result.getExtractionMethod()).isEqualTo(DocumentExtraction.ExtractionMethod.TIKA);
        assertThat(result.getExtractedText()).contains("INV-1024", "2450.50");
        assertThat(documentRepository.findById(saved.getId()).orElseThrow().getDocumentStatus())
                .isEqualTo(DocumentStatus.OCR_COMPLETED);
    }

    @Test
    @DisplayName("persists structured extraction JSON from the real deterministic extractor")
    void testStoreExtractedDataAsJson() throws Exception {
        Document saved = documentRepository.saveAndFlush(document);
        DocumentExtraction result = coordinator.processDocument(saved,
                new ByteArrayInputStream(invoicePdf("INV-1025")));

        assertThat(result.getExtractedData().get("invoiceNumber").asText()).isEqualTo("INV-1025");
        assertThat(result.getConfidenceScore()).isBetween(0.0, 100.0);
        assertThat(extractionRepository.findById(result.getId())).isPresent();
    }

    @Test
    @DisplayName("marks processing state complete after a real embedded-text extraction")
    void testStatusUpdateToProcessing() throws Exception {
        Document saved = documentRepository.saveAndFlush(document);
        coordinator.processDocument(saved, new ByteArrayInputStream(invoicePdf("INV-1026")));

        assertThat(documentRepository.findById(saved.getId()).orElseThrow().getDocumentStatus())
                .isEqualTo(DocumentStatus.OCR_COMPLETED);
    }

    @Test
    @DisplayName("persists extraction text and method for later retrieval")
    void testPersistExtractionRecord() throws Exception {
        Document saved = documentRepository.saveAndFlush(document);
        byte[] pdf = invoicePdf("INV-1027");

        DocumentExtraction result = coordinator.processDocument(saved, new ByteArrayInputStream(pdf));
        DocumentExtraction retrieved = extractionRepository.findById(result.getId()).orElseThrow();

        assertThat(retrieved.getExtractedText()).contains("INV-1027");
        assertThat(retrieved.getExtractionMethod()).isEqualTo(DocumentExtraction.ExtractionMethod.TIKA);
    }

    @Test
    @DisplayName("marks the document OCR_FAILED when the real stream read fails")
    void testHandleTikaExtractionFailure() {
        Document saved = documentRepository.saveAndFlush(document);
        InputStream brokenStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("deterministic test stream failure");
            }
        };

        assertThatThrownBy(() -> coordinator.processDocument(saved, brokenStream))
                .isInstanceOf(OcrProcessingException.class);
        assertThat(documentRepository.findById(saved.getId()).orElseThrow().getDocumentStatus())
                .isEqualTo(DocumentStatus.OCR_FAILED);
    }

    @Test
    @DisplayName("preserves previous extraction versions instead of overwriting them")
    void testExtractionVersioning() throws Exception {
        Document saved = documentRepository.saveAndFlush(document);
        DocumentExtraction first = coordinator.processDocument(saved,
                new ByteArrayInputStream(invoicePdf("INV-1028")));
        Document reloaded = documentRepository.findById(saved.getId()).orElseThrow();
        reloaded.setDocumentStatus(DocumentStatus.UPLOADED);
        documentRepository.saveAndFlush(reloaded);
        DocumentExtraction second = coordinator.processDocument(reloaded,
                new ByteArrayInputStream(invoicePdf("INV-1029")));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(extractionRepository.countByDocumentId(saved.getId())).isEqualTo(2);
    }

    private static byte[] invoicePdf(String invoiceNumber) throws Exception {
        String repeated = ("Invoice Number: " + invoiceNumber
                + " Invoice Date: 2026-10-28 Currency USD Amount 2450.50 Beneficiary XYZ Imports. ")
                .repeat(10);
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 10);
                content.newLineAtOffset(40, 750);
                content.showText(repeated);
                content.endText();
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
