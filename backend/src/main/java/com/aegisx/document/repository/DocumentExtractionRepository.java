package com.aegisx.document.repository;

import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.DocumentExtraction.ExtractionMethod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository for accessing document extraction records.
 * 
 * Handles all queries related to OCR output and structured extraction data.
 */
@Repository
public interface DocumentExtractionRepository extends JpaRepository<DocumentExtraction, UUID> {

    /**
     * Find all extractions for a specific document.
     * Returns results ordered by creation time (newest first).
     */
    @Query("SELECT de FROM DocumentExtraction de WHERE de.document.id = :documentId ORDER BY de.createdAt DESC")
    List<DocumentExtraction> findByDocumentId(UUID documentId);

    /**
     * Find the most recent extraction for a document.
     * Useful for retrieving the latest OCR result.
     */
    @Query(
        value = "SELECT * FROM document_extractions WHERE document_id = :documentId ORDER BY created_at DESC LIMIT 1",
        nativeQuery = true
    )
    Optional<DocumentExtraction> findLatestByDocumentId(UUID documentId);

    @Query("SELECT de FROM DocumentExtraction de WHERE de.document.transaction.id = :transactionId ORDER BY de.createdAt DESC")
    List<DocumentExtraction> findByTransactionId(UUID transactionId);

    default Optional<DocumentExtraction> findLatestByTransactionId(UUID transactionId) {
        return findByTransactionId(transactionId).stream().findFirst();
    }

    /**
     * Find all extractions using a specific method (e.g., TIKA or TESSERACT).
     * Useful for auditing which extraction method was used across documents.
     */
    List<DocumentExtraction> findByExtractionMethod(ExtractionMethod method);

    /**
     * Find extractions with confidence score >= minConfidence.
     * Allows optional filtering for analytics or reviewer workflows; this score is not a policy gate.
     */
    @Query(
        "SELECT de FROM DocumentExtraction de WHERE de.confidenceScore >= :minConfidence ORDER BY de.confidenceScore DESC"
    )
    List<DocumentExtraction> findHighConfidenceExtractions(Double minConfidence);

    /**
     * Count extractions for a document.
     * Indicates how many extraction attempts have been made.
     */
    long countByDocumentId(UUID documentId);
}
