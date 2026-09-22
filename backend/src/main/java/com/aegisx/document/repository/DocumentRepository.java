package com.aegisx.document.repository;

import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByTransactionId(UUID transactionId);

    List<Document> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    Optional<Document> findByIdAndTransactionId(UUID id, UUID transactionId);

    long countByTransactionId(UUID transactionId);

    long countByTransactionIdAndDocumentType(UUID transactionId, DocumentType documentType);
}
