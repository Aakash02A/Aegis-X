package com.aegisx.document.repository;

import com.aegisx.document.entity.Document;
import com.aegisx.document.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByTransactionId(UUID transactionId);

    List<Document> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    Optional<Document> findByIdAndTransactionId(UUID id, UUID transactionId);

    @Query("select document from Document document join fetch document.uploadedBy where document.id = :id")
    Optional<Document> findByIdWithUploader(@Param("id") UUID id);

    long countByTransactionId(UUID transactionId);

    long countByTransactionIdAndDocumentType(UUID transactionId, DocumentType documentType);
}
