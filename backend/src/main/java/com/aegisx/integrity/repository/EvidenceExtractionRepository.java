package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.EvidenceExtraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvidenceExtractionRepository extends JpaRepository<EvidenceExtraction, UUID> {

    /** Full extraction history for a document, oldest first, so a correction reads as a change. */
    List<EvidenceExtraction> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);

    List<EvidenceExtraction> findTopByDocumentIdAndHumanCorrectionFalseOrderByCreatedAtDesc(UUID documentId);

    List<EvidenceExtraction> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);

    void deleteByDocumentId(UUID documentId);
}
