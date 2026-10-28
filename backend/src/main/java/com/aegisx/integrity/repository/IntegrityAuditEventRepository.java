package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.IntegrityAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IntegrityAuditEventRepository extends JpaRepository<IntegrityAuditEvent, UUID> {

    Page<IntegrityAuditEvent> findByWorkspaceIdOrderByOccurredAtDesc(UUID workspaceId, Pageable pageable);

    long countByWorkspaceId(UUID workspaceId);
}
