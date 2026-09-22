package com.aegisx.document.ocr;

import com.aegisx.common.exception.OcrProcessingException;
import com.aegisx.common.exception.UnsupportedDocumentException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Performs OCR using Tesseract on scanned documents and images.
 * 
 * Supported formats:
 * - PNG
 * - JPG / JPEG
 * - Scanned PDFs (converted to images)
 * 
 * This service is invoked only when Tika extraction is insufficient.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TesseractOcrProcessor {

    @Value("${aegisx.ocr.tesseract-path:}")
    private String tesseractPath;

    @Value("${aegisx.ocr.language:eng}")
    private String ocrLanguage;

    // Supported image formats for OCR
    private static final List<String> SUPPORTED_FORMATS = Arrays.asList("png", "jpg", "jpeg");

    /**
     * Process an image file using Tesseract OCR.
     * 
     * @param imageStream the image file stream
     * @param mimeType the MIME type of the image (e.g., "image/png")
     * @return extracted text from the image
     * @throws OcrProcessingException if Tesseract fails
     * @throws UnsupportedDocumentException if the image format is not supported
     */
    public String processImage(InputStream imageStream, String mimeType) {
        log.debug("Starting Tesseract OCR for image: {}", mimeType);

        // Validate MIME type
        String format = extractFormatFromMimeType(mimeType);
        if (!SUPPORTED_FORMATS.contains(format.toLowerCase())) {
            throw new UnsupportedDocumentException("Unsupported image format: " + mimeType);
        }

        BufferedImage image;
        try {
            image = ImageIO.read(imageStream);
            if (image == null) {
                throw new OcrProcessingException("Failed to read image from stream");
            }
        } catch (IOException e) {
            throw new OcrProcessingException("Failed to load image for OCR", e);
        }

        return performOcr(image);
    }

    /**
     * Process a temporary image file using Tesseract OCR.
     * Used internally to handle file-based OCR operations.
     * 
     * @param imageFile the image file
     * @return extracted text from the image
     * @throws OcrProcessingException if Tesseract fails
     */
    public String processImageFile(File imageFile) {
        log.debug("Starting Tesseract OCR for file: {}", imageFile.getAbsolutePath());

        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                throw new OcrProcessingException("Failed to read image file: " + imageFile.getName());
            }
            return performOcr(image);
        } catch (IOException e) {
            throw new OcrProcessingException("Failed to load image file for OCR", e);
        }
    }

    /**
     * Perform OCR on a BufferedImage using Tesseract.
     * 
     * @param image the BufferedImage to OCR
     * @return extracted text
     * @throws OcrProcessingException if Tesseract fails
     */
    private String performOcr(BufferedImage image) {
        ITesseract tesseract = new Tesseract();

        // Set Tesseract data path if provided
        if (tesseractPath != null && !tesseractPath.isBlank()) {
            tesseract.setDatapath(tesseractPath);
        }

        // Set OCR language
        tesseract.setLanguage(ocrLanguage);

        try {
            String ocrResult = tesseract.doOCR(image);
            log.debug("Tesseract OCR completed. Text length: {} characters", ocrResult.length());
            return ocrResult.trim();
        } catch (TesseractException e) {
            log.error("Tesseract OCR failed", e);
            throw new OcrProcessingException("Tesseract OCR failed", e);
        }
    }

    /**
     * Extract file format from MIME type.
     * Examples: "image/png" → "png", "image/jpeg" → "jpeg"
     * 
     * @param mimeType the MIME type
     * @return the file format/extension
     */
    private String extractFormatFromMimeType(String mimeType) {
        if (mimeType == null || !mimeType.contains("/")) {
            return "";
        }
        return mimeType.split("/")[1].toLowerCase();
    }

    /**
     * Convert PDF stream to images for OCR processing.
     * This is a placeholder for future PDF-to-image conversion logic.
     * Currently not implemented; would require PDFBox or similar.
     * 
     * @param pdfStream the PDF file stream
     * @return list of temporary image files (caller must clean up)
     * @throws OcrProcessingException if conversion fails
     */
    public List<File> convertPdfToImages(InputStream pdfStream) {
        log.warn("PDF-to-image conversion not yet implemented");
        throw new OcrProcessingException("Scanned PDF processing requires PDF-to-image conversion (not yet implemented)");
    }
}
