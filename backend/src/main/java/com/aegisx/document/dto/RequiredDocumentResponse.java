package com.aegisx.document.dto;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequiredDocumentResponse {

    private UUID id;
    private UUID transactionId;
    private DocumentType documentType;
    private boolean fulfilled;
    private UUID documentId;

    public static RequiredDocumentResponse from(RequiredDocument requiredDocument) {
        return RequiredDocumentResponse.builder()
                .id(requiredDocument.getId())
                .transactionId(requiredDocument.getTransaction() != null ? requiredDocument.getTransaction().getId() : null)
                .documentType(requiredDocument.getDocumentType())
                .fulfilled(requiredDocument.isFulfilled())
                .documentId(requiredDocument.getDocument() != null ? requiredDocument.getDocument().getId() : null)
                .build();
    }
}
