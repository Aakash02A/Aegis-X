package com.aegisx.document.ocr;

import com.aegisx.common.exception.OcrProcessingException;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

/**
 * Extracts embedded text from documents using Apache Tika.
 * 
 * Supported formats:
 * - PDF (native text)
 * - Images with embedded text
 * 
 * This is the first step in the OCR pipeline. If sufficient text is extracted,
 * Tesseract OCR can be skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TikaTextExtractor {

    /**
     * Maximum length of text to extract (prevents memory bloat).
     * Compliance documents are typically < 500KB of text.
     */
    private static final int MAX_TEXT_LENGTH = 1_000_000; // 1MB of text

    /**
     * Extract text from a document stream using Apache Tika.
     * 
     * @param inputStream the document file stream
     * @return extracted text, or empty string if extraction fails or produces no text
     * @throws OcrProcessingException if Tika fails to parse the document
     */
    public String extractText(InputStream inputStream) {
        log.debug("Starting Tika text extraction");

        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, metadata, context);

            String extractedText = handler.toString().trim();
            log.debug("Tika extraction completed. Text length: {} characters", extractedText.length());

            return extractedText;
        } catch (TikaException e) {
            log.warn("Tika parsing error", e);
            throw new OcrProcessingException("Tika failed to parse document", e);
        } catch (SAXException e) {
            log.warn("SAX parsing error during Tika extraction", e);
            throw new OcrProcessingException("SAX error during text extraction", e);
        } catch (IOException e) {
            log.warn("IO error during Tika extraction", e);
            throw new OcrProcessingException("IO error during text extraction", e);
        } catch (Exception e) {
            log.error("Unexpected error during Tika extraction", e);
            throw new OcrProcessingException("Unexpected error during text extraction", e);
        }
    }

    /**
     * Determine if the extracted text is sufficient to skip OCR.
     * 
     * Heuristic:
     * - If text is empty or very short (< 100 chars), likely scanned → requires OCR
     * - If text is substantial (≥ 500 chars), likely has embedded text → skip OCR
     * 
     * @param extractedText the text extracted by Tika
     * @return true if OCR can be skipped, false if OCR is needed
     */
    public boolean isSufficientText(String extractedText) {
        int threshold = 500;
        boolean sufficient = extractedText != null && extractedText.length() >= threshold;
        log.debug("Text sufficiency check: {} characters. Sufficient={}", 
            extractedText != null ? extractedText.length() : 0, sufficient);
        return sufficient;
    }
}
