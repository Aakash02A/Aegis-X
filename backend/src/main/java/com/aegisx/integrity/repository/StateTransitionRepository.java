package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.entity.StateTransitionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StateTransitionRepository extends JpaRepository<StateTransitionRecord, UUID> {

    List<StateTransitionRecord> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(ResourceType entityType, UUID entityId);

    List<StateTransitionRecord> findByWorkspaceIdAndConflictTrueOrderByOccurredAtDesc(UUID workspaceId);

    long countByWorkspaceIdAndConflictTrue(UUID workspaceId);

    List<StateTransitionRecord> findAllByWorkspaceIdOrderByOccurredAtDesc(UUID workspaceId);
}
