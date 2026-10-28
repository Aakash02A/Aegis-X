package com.aegisx.integrity.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates real PDF evidence documents for the evidence tests.
 *
 * <p>Deliberately real PDFs rather than plain text renamed to {@code .pdf}. The upload path validates
 * the MIME type from the bytes with Tika, so a mislabelled text file would be rejected — which is the
 * correct behaviour and would make these tests pass for the wrong reason. Generating a genuine PDF
 * with an embedded text layer means the tests exercise the production configuration (the
 * {@code application/pdf} allow-list entry) and the production reader.</p>
 *
 * <p>Bodies are padded past the project's "sufficient text" threshold so Apache Tika's text layer is
 * used and no optical character recognition is attempted. That keeps the tests independent of whether
 * Tesseract is installed on the machine running them. Scanned-document behaviour is a separate,
 * explicitly documented limitation rather than something these fixtures pretend to cover.</p>
 */
public final class EvidenceFixtures {

    private EvidenceFixtures() {
    }

    /** A commercial invoice whose total and currency agree with {@code totalMinor}. */
    public static byte[] invoicePdf(String invoiceNumber, String orderReference, String currency,
                                    String totalMajor) {
        List<String> lines = new ArrayList<>();
        lines.add("COMMERCIAL INVOICE");
        lines.add("Invoice Number: " + invoiceNumber);
        lines.add("Invoice Date: 2026-10-11");
        lines.add("Order Reference: " + orderReference);
        lines.add("Currency: " + currency);
        lines.add("");
        lines.add("Description                Quantity        Unit Price        Line Total");
        lines.add("Widget assembly            120             18.50             " + currency + " 2220.00");
        lines.add("Freight and handling       1               180.00            " + currency + " 180.00");
        lines.add("Packing materials          40              2.25              " + currency + " 90.00");
        lines.add("");
        lines.add("Subtotal: " + currency + " 2490.00");
        lines.add("Tax: " + currency + " 0.00");
        lines.add("Total Amount Due: " + currency + " " + totalMajor);
        lines.add("");
        lines.add("Payment terms: 30 days from invoice date.");
        lines.add("Goods shipped under Incoterms 2020, FOB origin.");
        lines.add("This document is issued for customs and accounting purposes and states the");
        lines.add("commercial value of the goods described above. Any discrepancy between this");
        lines.add("invoice and the purchase order should be raised with the supplier before");
        lines.add("payment is released, so that the corrected document can be reissued.");
        return render(lines);
    }

    /** A delivery or shipping note that states no total at all. */
    public static byte[] shippingBillPdf(String orderReference, String documentNumber) {
        List<String> lines = new ArrayList<>();
        lines.add("SHIPPING BILL / DELIVERY NOTE");
        lines.add("Document Number: " + documentNumber);
        lines.add("Order Reference: " + orderReference);
        lines.add("Date of despatch: 2026-10-10");
        lines.add("");
        lines.add("Carrier: Northwind Freight");
        lines.add("Consignment: 3 pallets, 120 cartons, gross weight 640 kg");
        lines.add("Place of receipt: Origin warehouse, bay 4");
        lines.add("Place of delivery: Consignee premises, dock 2");
        lines.add("");
        lines.add("Received the goods listed above in apparent good order and condition unless");
        lines.add("otherwise noted. This document evidences movement of the consignment and does");
        lines.add("not state a commercial value, nor does it constitute an invoice or a receipt");
        lines.add("for payment. Signature of the consignee confirms delivery only.");
        return render(lines);
    }

    /** A document whose stated total deliberately differs from the order amount. */
    public static byte[] invoicePdfWithMismatchedTotal(String invoiceNumber, String orderReference,
                                                        String currency, String statedTotalMajor) {
        return invoicePdf(invoiceNumber, orderReference, currency, statedTotalMajor);
    }

    /** A document denominated in a different currency from the order. */
    public static byte[] invoicePdfInCurrency(String invoiceNumber, String orderReference,
                                              String currency, String totalMajor) {
        return invoicePdf(invoiceNumber, orderReference, currency, totalMajor);
    }

    /**
     * A file that claims to be a PDF but cannot be parsed.
     *
     * <p>Chosen for the unreadable-document test because it fails deterministically on any machine:
     * Tika identifies it as {@code application/pdf} from the header, its parser cannot read it, and
     * the fallback optical recognition path cannot read it either whether or not Tesseract happens to
     * be installed. A short-but-valid document would instead depend on the local Tesseract install and
     * would pass or fail by accident.</p>
     */
    public static byte[] corruptPdf() {
        String body = "%PDF-1.4\n" + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\n"
                + "this is not a valid PDF body and no cross-reference table follows\n"
                + "%%EOF\n";
        return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Bytes Tika will identify as an archive, used to prove the allow-list rejects it. */
    public static byte[] unsupportedArchive() {
        // A minimal ZIP local file header plus content; Tika reports application/zip.
        byte[] header = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00};
        byte[] body = "not a supported evidence document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = new byte[header.length + body.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(body, 0, result, header.length, body.length);
        return result;
    }

    /** Deliberately empty content, used to prove an empty upload is refused. */
    public static byte[] empty() {
        return new byte[0];
    }

    /** Lines per page at 8pt with 10.5pt leading on a letter page. */
    private static final int LINES_PER_PAGE = 68;

    private static byte[] render(List<String> lines) {
        try (PDDocument document = new PDDocument()) {
            for (int start = 0; start < lines.size(); start += LINES_PER_PAGE) {
                PDPage page = new PDPage();
                document.addPage(page);
                List<String> pageLines = lines.subList(start,
                        Math.min(start + LINES_PER_PAGE, lines.size()));
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    // PDFBox 2.x constant. The project is held on the 2.0.x line because Apache Tika
                    // 2.9.2's PDF parser is built against it; see pdfbox.version in pom.xml.
                    content.setFont(PDType1Font.HELVETICA, 8);
                    content.setLeading(10.5f);
                    content.newLineAtOffset(40, 760);
                    for (String line : pageLines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            if (document.getNumberOfPages() == 0) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the PDF test fixture", e);
        }
    }
}
