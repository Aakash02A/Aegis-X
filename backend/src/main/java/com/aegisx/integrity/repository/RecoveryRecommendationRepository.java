package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecoveryRecommendationRepository extends JpaRepository<RecoveryRecommendation, UUID> {

    List<RecoveryRecommendation> findByExceptionIdOrderByCreatedAtAsc(UUID exceptionId);

    List<RecoveryRecommendation> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    boolean existsByExceptionIdAndType(UUID exceptionId, com.aegisx.integrity.domain.RecommendationType type);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatus(UUID workspaceId, RecommendationStatus status);

    long countByWorkspaceIdAndCategory(UUID workspaceId, com.aegisx.integrity.domain.RecommendationCategory category);
}
