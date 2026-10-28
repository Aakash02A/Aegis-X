package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.EvidenceReadinessStatus;
import com.aegisx.integrity.entity.EvidenceReadiness;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EvidenceReadinessRepository extends JpaRepository<EvidenceReadiness, UUID> {

    Optional<EvidenceReadiness> findByWorkspaceIdAndOrderId(UUID workspaceId, UUID orderId);

    List<EvidenceReadiness> findAllByWorkspaceIdOrderByLastAssessedAtDesc(UUID workspaceId);

    List<EvidenceReadiness> findAllByWorkspaceIdAndStatus(UUID workspaceId, EvidenceReadinessStatus status);

    long countByWorkspaceIdAndStatus(UUID workspaceId, EvidenceReadinessStatus status);

    long countByWorkspaceIdAndStatusIn(UUID workspaceId, java.util.Collection<EvidenceReadinessStatus> statuses);
}
