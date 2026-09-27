package com.aegisx.evidence.dto;

import com.aegisx.document.entity.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for missing documents information.
 * Provides details about which documents are still required for a transaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissingDocumentsResponse {

    private UUID transactionId;
    private List<MissingDocumentInfo> missingDocuments;
    private boolean evidenceComplete;
    private int totalRequired;
    private int totalUploaded;
    private double completionPercentage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingDocumentInfo {
        private DocumentType documentType;
        private String displayName;
        private String description;
        private boolean required;
        private boolean uploaded;
        private String uploadHint;

        /**
         * Creates missing document info from document type.
         */
        public static MissingDocumentInfo fromDocumentType(DocumentType documentType, boolean required, boolean uploaded) {
            return MissingDocumentInfo.builder()
                    .documentType(documentType)
                    .displayName(documentType.getDisplayName())
                    .description(getDocumentDescription(documentType))
                    .required(required)
                    .uploaded(uploaded)
                    .uploadHint(getUploadHint(documentType))
                    .build();
        }

        private static String getDocumentDescription(DocumentType documentType) {
            return switch (documentType) {
                case COMMERCIAL_INVOICE, INVOICE -> 
                    "Commercial invoice showing transaction details, amounts, and party information";
                case SHIPPING_BILL -> 
                    "Shipping bill or bill of lading proving goods shipment";
                case PACKING_LIST -> 
                    "Detailed packing list showing shipped goods breakdown";
                case CERTIFICATE_OF_ORIGIN -> 
                    "Certificate of origin for international trade compliance";
                default -> 
                    "Supporting documentation for transaction compliance";
            };
        }

        private static String getUploadHint(DocumentType documentType) {
            return switch (documentType) {
                case COMMERCIAL_INVOICE, INVOICE -> 
                    "Upload clear PDF or image of the commercial invoice";
                case SHIPPING_BILL -> 
                    "Upload shipping bill, bill of lading, or airway bill";
                case PACKING_LIST -> 
                    "Upload detailed packing list or goods manifest";
                case CERTIFICATE_OF_ORIGIN -> 
                    "Upload certificate of origin issued by authorized body";
                default -> 
                    "Upload clear PDF or image document";
            };
        }
    }

    /**
     * Creates response from missing documents list and checklist info.
     */
    public static MissingDocumentsResponse create(UUID transactionId, 
                                                  List<DocumentType> missingTypes,
                                                  int totalRequired, 
                                                  int totalUploaded) {
        List<MissingDocumentInfo> missingInfos = missingTypes.stream()
                .map(type -> MissingDocumentInfo.fromDocumentType(type, true, false))
                .toList();

        double completionPercentage = totalRequired == 0 ? 100.0 : (totalUploaded * 100.0 / totalRequired);

        return MissingDocumentsResponse.builder()
                .transactionId(transactionId)
                .missingDocuments(missingInfos)
                .evidenceComplete(missingTypes.isEmpty())
                .totalRequired(totalRequired)
                .totalUploaded(totalUploaded)
                .completionPercentage(completionPercentage)
                .build();
    }
}