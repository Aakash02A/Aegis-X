package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.ReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewActionRepository extends JpaRepository<ReviewAction, UUID> {

    List<ReviewAction> findByExceptionIdOrderByCreatedAtAsc(UUID exceptionId);

    List<ReviewAction> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);
}
