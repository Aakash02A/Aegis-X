package com.aegisx.document.ocr;

import com.aegisx.common.exception.OcrProcessingException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resource-limit tests for OCR.
 *
 * <p>Upload size is not a bound on work: a small PDF can describe thousands of pages, each of which
 * would be rendered and OCR'd. These tests assert the refusal happens before any rendering, so the
 * limit actually protects the worker rather than merely failing after the cost has been paid.</p>
 */
@DisplayName("OCR resource limits")
class TesseractOcrProcessorLimitsTest {

    private final TesseractOcrProcessor processor = new TesseractOcrProcessor();

    @Test
    @DisplayName("a PDF with more pages than the limit is refused without rendering a page")
    void oversizedPdfIsRefused() throws Exception {
        byte[] pdf = pdfWithPages(51);

        assertThatThrownBy(() -> processor.processPdf(new ByteArrayInputStream(pdf)))
                .isInstanceOf(OcrProcessingException.class)
                .hasMessageContaining("exceeds the OCR limit")
                .hasMessageContaining("pages");
    }

    private static byte[] pdfWithPages(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
