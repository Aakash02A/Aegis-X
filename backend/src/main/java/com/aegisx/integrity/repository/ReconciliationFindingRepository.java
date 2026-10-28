package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.entity.ReconciliationFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, UUID> {

    List<ReconciliationFinding> findByRunIdOrderByCreatedAtAsc(UUID runId);

    List<ReconciliationFinding> findByExceptionIdOrderByCreatedAtDesc(UUID exceptionId);

    Page<ReconciliationFinding> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    long countByWorkspaceIdAndFindingType(UUID workspaceId, FindingType findingType);

    long countByRunId(UUID runId);
}
