package com.aegisx.document.service;

import com.aegisx.audit.entity.AuditEventType;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.entity.ActorType;
import com.aegisx.audit.service.AuditService;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.exception.InvalidDocumentException;
import com.aegisx.common.filter.CorrelationIdFilter;
import com.aegisx.document.dto.DocumentResponse;
import com.aegisx.document.dto.EvidenceStatusResponse;
import com.aegisx.document.dto.RequiredDocumentResponse;
import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentStatus;
import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.document.repository.DocumentRepository;
import com.aegisx.document.repository.RequiredDocumentRepository;
import com.aegisx.storage.service.StorageService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.entity.TransitionActor;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.transaction.service.TransactionStateMachine;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.slf4j.MDC;
import org.springframework.core.io.Resource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final RequiredDocumentRepository requiredDocumentRepository;
    private final StorageService storageService;
    private final TransactionStateMachine transactionStateMachine;
    private final AuditService auditService;

    private static final List<DocumentType> REQUIRED_DOCUMENT_TYPES = List.of(
            DocumentType.INVOICE,
            DocumentType.SHIPPING_PROOF,
            DocumentType.EXPORT_DECLARATION
    );

    @Transactional
    public void initializeChecklist(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        List<RequiredDocument> existing = requiredDocumentRepository.findByTransactionId(transactionId);
        if (!existing.isEmpty()) {
            return;
        }

        List<RequiredDocument> missing = new ArrayList<>();
        for (DocumentType type : REQUIRED_DOCUMENT_TYPES) {
            RequiredDocument row = RequiredDocument.builder()
                    .transaction(transaction)
                    .documentType(type)
                    .fulfilled(false)
                    .build();
            missing.add(row);
        }

        requiredDocumentRepository.saveAll(missing);
        log.info("Initialized evidence checklist for transaction {} with {} required documents", transactionId, missing.size());
    }

    @Transactional
    public DocumentResponse uploadDocument(UUID transactionId, MultipartFile file, DocumentType documentType, String userEmail) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        User uploader = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + userEmail));

        validateDocument(file, documentType, transaction);

        String storagePath;
        try {
            storagePath = storageService.store(file, transactionId, documentType);
        } catch (IOException ex) {
            throw new InvalidDocumentException("Unable to store uploaded file: " + ex.getMessage());
        }

        String fileHash = computeSha256(file);
        String mimeType = detectMimeType(file);

        Document document = Document.builder()
                .transaction(transaction)
                .documentType(documentType)
                .documentStatus(DocumentStatus.UPLOADED)
                .originalFilename(file.getOriginalFilename())
                .storedFilename(storagePath.substring(storagePath.lastIndexOf('/') + 1))
                .mimeType(mimeType)
                .fileSize(file.getSize())
                .fileHash(fileHash)
                .storagePath(storagePath)
                .uploadedBy(uploader)
                .build();

        Document saved = documentRepository.save(document);

        // Record audit event for document upload
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY);
        ObjectNode eventData = JsonNodeFactory.instance.objectNode();
        eventData.put("documentType", documentType.name());
        eventData.put("originalFilename", file.getOriginalFilename());
        eventData.put("fileSize", file.getSize());
        eventData.put("mimeType", mimeType);
        eventData.put("fileHash", fileHash);

        auditService.recordUserEvent(
            transaction,
            AuditEventType.DOCUMENT_UPLOADED,
            AuditModule.DOCUMENT,
            uploader.getId(),
            correlationId,
            eventData
        );

        updateChecklistStatus(transactionId, documentType, saved);

        if (transaction.getStatus() == TransactionStatus.EVIDENCE_PENDING) {
            transactionStateMachine.transition(transaction, TransactionStatus.OCR_PROCESSING, TransitionActor.DOCUMENT_SERVICE, "Document uploaded and validated for OCR");
            transactionRepository.save(transaction);
        }

        return DocumentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public EvidenceStatusResponse getEvidenceStatus(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        List<RequiredDocument> allRequired = requiredDocumentRepository.findByTransactionId(transactionId);
        if (allRequired.isEmpty()) {
            initializeChecklist(transactionId);
            allRequired = requiredDocumentRepository.findByTransactionId(transactionId);
        }

        List<RequiredDocumentResponse> requiredResponses = new ArrayList<>();
        int fulfilledCount = 0;
        for (RequiredDocument required : allRequired) {
            requiredResponses.add(RequiredDocumentResponse.from(required));
            if (required.isFulfilled()) {
                fulfilledCount++;
            }
        }

        int totalRequired = requiredResponses.size();
        EvidenceStatusResponse response = EvidenceStatusResponse.builder()
                .transactionStatus(transaction.getStatus())
                .totalRequired(totalRequired)
                .fulfilledCount(fulfilledCount)
                .remainingCount(totalRequired - fulfilledCount)
                .complete(fulfilledCount == totalRequired)
                .requiredDocuments(requiredResponses)
                .build();

        return response;
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listDocuments(UUID transactionId) {
        return documentRepository.findByTransactionIdOrderByCreatedAtDesc(transactionId)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID transactionId, UUID documentId, String userEmail) {
        findAuthorizedTransaction(transactionId, findUser(userEmail));
        Document document = documentRepository.findByIdAndTransactionId(documentId, transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found for transaction: " + transactionId));
        return DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public Resource loadDocument(UUID transactionId, UUID documentId, String userEmail) {
        findAuthorizedTransaction(transactionId, findUser(userEmail));
        Document document = documentRepository.findByIdAndTransactionId(documentId, transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found for transaction: " + transactionId));
        return storageService.load(document.getStoragePath());
    }

    private User findUser(String userEmail) {
        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + userEmail));
    }

    private Transaction findAuthorizedTransaction(UUID transactionId, User user) {
        if (user.getRole() == Role.MERCHANT) {
            return transactionRepository.findByIdAndMerchantId(transactionId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
        }
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
    }

    private void validateDocument(MultipartFile file, DocumentType documentType, Transaction transaction) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("Uploaded file is empty");
        }
        if (documentType == null) {
            throw new InvalidDocumentException("Document type is required");
        }
        if (transaction == null || transaction.getId() == null) {
            throw new InvalidDocumentException("Transaction is required");
        }
        if (file.getSize() <= 0) {
            throw new InvalidDocumentException("File size must be greater than zero");
        }

        String mimeType = detectMimeType(file);
        if (!isSupportedMimeType(mimeType)) {
            throw new InvalidDocumentException("Unsupported document MIME type: " + mimeType + ". Allowed: PDF, JPEG, PNG");
        }

        if (documentRepository.countByTransactionIdAndDocumentType(transaction.getId(), documentType) > 0) {
            throw new InvalidDocumentException("A document of type " + documentType + " is already uploaded for this transaction");
        }
    }

    private void updateChecklistStatus(UUID transactionId, DocumentType documentType, Document document) {
        RequiredDocument required = requiredDocumentRepository.findByTransactionIdAndDocumentType(transactionId, documentType)
                .orElse(null);
        if (required != null) {
            required.setFulfilled(true);
            required.setDocument(document);
            requiredDocumentRepository.save(required);
        }
    }

    private String detectMimeType(MultipartFile file) {
        try {
            Tika tika = new Tika();
            return tika.detect(file.getInputStream());
        } catch (IOException ex) {
            throw new InvalidDocumentException("Unable to detect MIME type for uploaded file");
        }
    }

    private String computeSha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = file.getBytes();
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new InvalidDocumentException("Unable to compute file hash");
        }
    }

    private boolean isSupportedMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return mimeType.equalsIgnoreCase("application/pdf")
                || mimeType.equalsIgnoreCase("image/jpeg")
                || mimeType.equalsIgnoreCase("image/png");
    }
}
