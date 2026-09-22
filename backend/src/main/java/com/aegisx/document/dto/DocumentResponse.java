package com.aegisx.document.dto;

import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentStatus;
import com.aegisx.document.entity.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    private UUID id;
    private UUID transactionId;
    private DocumentType documentType;
    private DocumentStatus documentStatus;
    private String originalFilename;
    private String storedFilename;
    private String mimeType;
    private Long fileSize;
    private String fileHash;
    private String storagePath;
    private UUID uploadedBy;
    private Instant createdAt;

    public static DocumentResponse from(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .transactionId(document.getTransaction() != null ? document.getTransaction().getId() : null)
                .documentType(document.getDocumentType())
                .documentStatus(document.getDocumentStatus())
                .originalFilename(document.getOriginalFilename())
                .storedFilename(document.getStoredFilename())
                .mimeType(document.getMimeType())
                .fileSize(document.getFileSize())
                .fileHash(document.getFileHash())
                .storagePath(document.getStoragePath())
                .uploadedBy(document.getUploadedBy() != null ? document.getUploadedBy().getId() : null)
                .createdAt(document.getCreatedAt())
                .build();
    }
}
