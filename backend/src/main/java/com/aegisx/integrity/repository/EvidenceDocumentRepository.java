package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.EvidenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvidenceDocumentRepository extends JpaRepository<EvidenceDocument, UUID> {

    /** Every document ever attached to the order, newest first, including superseded ones. */
    List<EvidenceDocument> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /** Only the documents that are current for their type. This is what readiness and matching use. */
    List<EvidenceDocument> findAllByOrderIdAndSupersededAtIsNullOrderByCreatedAtDesc(UUID orderId);

    List<EvidenceDocument> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<EvidenceDocument> findAllByWorkspaceIdAndSupersededAtIsNull(UUID workspaceId);

    Optional<EvidenceDocument> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /** The current document of a type for an order, if one exists. */
    Optional<EvidenceDocument> findByOrderIdAndDocumentTypeAndSupersededAtIsNull(
            UUID orderId, com.aegisx.document.entity.DocumentType documentType);

    /** Any active document in the workspace with these exact bytes. Real duplicate detection. */
    List<EvidenceDocument> findAllByWorkspaceIdAndFileHashAndSupersededAtIsNull(
            UUID workspaceId, String fileHash);

    long countByWorkspaceIdAndSupersededAtIsNull(UUID workspaceId);

    long countByOrderIdAndSupersededAtIsNull(UUID orderId);

    long countByWorkspaceIdAndProcessingStatus(UUID workspaceId,
                                              com.aegisx.integrity.domain.EvidenceProcessingStatus status);
}
