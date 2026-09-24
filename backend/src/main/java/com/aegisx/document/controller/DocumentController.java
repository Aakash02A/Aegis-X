package com.aegisx.document.controller;

import com.aegisx.document.dto.DocumentResponse;
import com.aegisx.document.dto.EvidenceStatusResponse;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/{transactionId}/documents")
    public ResponseEntity<DocumentResponse> uploadDocument(
            @PathVariable UUID transactionId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") DocumentType documentType,
            Authentication authentication) {
        DocumentResponse response = documentService.uploadDocument(transactionId, file, documentType, authentication.getName());
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/{transactionId}/documents")
    public ResponseEntity<List<DocumentResponse>> listDocuments(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        // Security: Verify user has access to this transaction
        String email = authentication.getName();
        return ResponseEntity.ok(documentService.listDocuments(transactionId, email));
    }

    @GetMapping("/{transactionId}/documents/{documentId}/download")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable UUID transactionId,
            @PathVariable UUID documentId,
            Authentication authentication) {
        DocumentResponse document = documentService.getDocument(transactionId, documentId, authentication.getName());
        Resource resource = documentService.loadDocument(transactionId, documentId, authentication.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.getOriginalFilename() + "\"")
                .body(resource);
    }

    @GetMapping("/{transactionId}/evidence-status")
    public ResponseEntity<EvidenceStatusResponse> getEvidenceStatus(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        // Security: Verify user has access to this transaction
        String email = authentication.getName();
        return ResponseEntity.ok(documentService.getEvidenceStatus(transactionId, email));
    }
}
