import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** Generates deterministic text PDFs for the legacy evidence/policy HTTP flow. */
public class GenPolicyDocument {
    public static void main(String[] args) throws Exception {
        String type = args[0];
        String documentNumber = args[1];
        String orderReference = args[2];
        String currency = args[3];
        String total = args[4];
        String beneficiary = args[5];
        Path output = Path.of(args[6]);
        String[] lines = switch (type) {
            case "INVOICE" -> new String[] {
                "COMMERCIAL INVOICE",
                "Invoice Number: " + documentNumber,
                "Invoice Date: 2026-10-11",
                "Order Reference: " + orderReference,
                "Beneficiary: " + beneficiary,
                "Currency: " + currency,
                "Description: Exported goods and delivery service",
                "Total Amount Due: " + currency + " " + total,
                "Issued for customs and accounting records.",
                "Contact the exporter if any invoice detail requires correction."
            };
            case "SHIPPING_PROOF" -> new String[] {
                "SHIPPING PROOF",
                "Document Number: " + documentNumber,
                "Order Reference: " + orderReference,
                "Carrier: Northwind Freight",
                "Despatch Date: 2026-10-10",
                "This document records transit of the described consignment.",
                "It is not a payment receipt or a commercial invoice."
            };
            case "EXPORT_DECLARATION" -> new String[] {
                "EXPORT DECLARATION",
                "Declaration Number: " + documentNumber,
                "Order Reference: " + orderReference,
                "Exporter: " + beneficiary,
                "Currency: " + currency,
                "Goods are declared for export under the stated transaction reference.",
                "This declaration is provided as supporting compliance evidence."
            };
            default -> throw new IllegalArgumentException("Unsupported policy evidence type");
        };
        // The production pipeline deliberately falls back to native Tesseract below 500
        // extracted characters. Keep these deterministic E2E fixtures on the embedded-text
        // path so they do not depend on a host-specific Tesseract installation.
        String[] supportingNotes = {
            "This record supports review of the stated transaction and its evidence.",
            "The identifiers and amounts above should be checked against the submitted order.",
            "A reviewer may compare this record with other documents for consistency.",
            "The evidence describes the goods and parties associated with this transaction.",
            "Any difference between the submitted records should be escalated for review.",
            "This text is included as part of the document record for searchable extraction.",
            "The document does not authorize or perform any transfer of funds.",
            "The final policy outcome is determined by the application after evidence review."
        };
        String[] completeLines = java.util.stream.Stream.concat(
            java.util.Arrays.stream(lines), java.util.Arrays.stream(supportingNotes)).toArray(String[]::new);

        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 10);
                content.setLeading(16);
                content.newLineAtOffset(40, 740);
                for (String line : completeLines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            pdf.save(output.toFile());
        }
        System.out.println("wrote policy evidence PDF");
    }
}
