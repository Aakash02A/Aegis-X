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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
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

    /**
     * Upper bound on the pages OCR will render from a single PDF.
     *
     * <p>An uploaded PDF is capped in bytes, not in work: a few kilobytes can describe thousands of
     * pages, each of which is rendered and OCR'd on the request thread. The cap keeps one upload from
     * occupying a worker indefinitely. Longer documents must be split, which is stated in the error.</p>
     */
    @Value("${aegisx.ocr.max-pages:50}")
    private int maxPages = 50;

    /**
     * Upper bound on the pixels decoded from one image or rendered from one PDF page.
     *
     * <p>Compressed image formats can declare enormous dimensions in a tiny file (a "decompression
     * bomb"). The size check on the upload sees only the compressed bytes, so the dimensions are
     * inspected before decoding and a page that would exceed this budget is refused instead of being
     * materialised in memory.</p>
     */
    @Value("${aegisx.ocr.max-image-pixels:40000000}")
    private long maxImagePixels = 40_000_000L;

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

        BufferedImage image = readImage(imageStream, "stream");
        return performOcr(image);
    }

    /** Render and OCR each page of a scanned PDF. */
    public String processPdf(InputStream pdfStream) {
        // PDFBox 2.x API. The project is deliberately held on the 2.0.x line because Apache Tika
        // 2.9.2's PDF parser is built against it; see the pdfbox.version property in pom.xml.
        try (PDDocument pdf = PDDocument.load(pdfStream.readAllBytes())) {
            if (pdf.getNumberOfPages() > maxPages) {
                throw new OcrProcessingException("The PDF has " + pdf.getNumberOfPages()
                        + " pages, which exceeds the OCR limit of " + maxPages
                        + ". Split the document and upload the relevant pages.");
            }
            PDFRenderer renderer = new PDFRenderer(pdf);
            StringBuilder text = new StringBuilder();
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                requireRenderablePage(pdf.getPage(page), page);
                BufferedImage image = renderer.renderImageWithDPI(page, 180);
                String pageText = performOcr(image);
                if (!pageText.isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(pageText);
                }
            }
            return text.toString();
        } catch (IOException e) {
            throw new OcrProcessingException("Failed to render scanned PDF for OCR", e);
        }
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

        try (InputStream stream = Files.newInputStream(imageFile.toPath())) {
            return performOcr(readImage(stream, "file " + imageFile.getName()));
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
        } catch (Error e) {
            // The native OCR stack reports some failures as java.lang.Error rather than as an
            // exception (observed in production as "Invalid memory access" when the traineddata
            // files are absent). An Error escaping this boundary turned a document upload into an
            // HTTP 500 and left the document stuck in PROCESSING, so it is converted into the same
            // checked-by-caller failure as every other OCR problem. VM-level errors stay fatal.
            if (e instanceof VirtualMachineError || e instanceof ThreadDeath) {
                throw e;
            }
            log.error("Tesseract OCR failed in native code", e);
            throw new OcrProcessingException(
                    "Tesseract OCR could not run in this environment: " + e.getMessage(), e);
        }
    }

    /**
     * Reads an image after checking its declared dimensions.
     *
     * <p>The check happens through an {@link javax.imageio.ImageReader} before {@code read()}, because
     * decoding is the expensive step: a small, highly compressed file can expand into an image far
     * larger than the upload limit suggests.</p>
     */
    private BufferedImage readImage(InputStream stream, String source) {
        try (javax.imageio.stream.ImageInputStream imageInput = ImageIO.createImageInputStream(stream)) {
            if (imageInput == null) {
                throw new OcrProcessingException("Failed to read image from " + source);
            }
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new OcrProcessingException("Unsupported or unreadable image from " + source);
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                requirePixelBudget(reader.getWidth(0), reader.getHeight(0), source);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new OcrProcessingException("Failed to load image for OCR", e);
        }
    }

    /** Refuses a PDF page whose rendered size would exceed the pixel budget, before rendering it. */
    private void requireRenderablePage(PDPage page, int pageIndex) {
        float widthPoints = page.getMediaBox().getWidth();
        float heightPoints = page.getMediaBox().getHeight();
        long widthPixels = Math.round(widthPoints / 72f * 180);
        long heightPixels = Math.round(heightPoints / 72f * 180);
        requirePixelBudget(widthPixels, heightPixels, "PDF page " + (pageIndex + 1));
    }

    private void requirePixelBudget(long width, long height, String source) {
        long budget = maxImagePixels > 0 ? maxImagePixels : 40_000_000L;
        if (width <= 0 || height <= 0) {
            throw new OcrProcessingException("The image from " + source + " has no usable dimensions");
        }
        // Divide rather than multiply so a hostile declared size cannot overflow the check.
        if (width > budget || height > budget || width > budget / height) {
            throw new OcrProcessingException("The image from " + source + " is " + width + "x" + height
                    + " pixels, which exceeds the OCR limit of " + budget + " pixels");
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

}
