package com.aegisx.document.repository;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RequiredDocumentRepository extends JpaRepository<RequiredDocument, UUID> {

    List<RequiredDocument> findByTransactionId(UUID transactionId);

    Optional<RequiredDocument> findByTransactionIdAndDocumentType(UUID transactionId, DocumentType documentType);

    long countByTransactionIdAndFulfilledTrue(UUID transactionId);
}
