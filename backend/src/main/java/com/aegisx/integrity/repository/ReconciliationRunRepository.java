package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    List<ReconciliationRun> findAllByWorkspaceIdOrderByStartedAtDesc(UUID workspaceId);

    Optional<ReconciliationRun> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<ReconciliationRun> findTopByWorkspaceIdOrderByStartedAtDesc(UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);
}
