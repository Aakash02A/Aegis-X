package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.ExceptionSeverity;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.entity.ExceptionCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, UUID> {

    Optional<ExceptionCase> findByWorkspaceIdAndDedupeKey(UUID workspaceId, String dedupeKey);

    List<ExceptionCase> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    /** Every case raised against one order, used by the four-status order view. */
    List<ExceptionCase> findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(UUID workspaceId, UUID orderId);

    Page<ExceptionCase> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    Page<ExceptionCase> findByWorkspaceIdAndStatusInOrderBySeverityAscCreatedAtAsc(UUID workspaceId, List<ExceptionStatus> statuses, Pageable pageable);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndStatusIn(UUID workspaceId, List<ExceptionStatus> statuses);

    long countByWorkspaceIdAndStatus(UUID workspaceId, ExceptionStatus status);

    long countByWorkspaceIdAndSeverity(UUID workspaceId, ExceptionSeverity severity);

    long countByWorkspaceIdAndFindingType(UUID workspaceId, FindingType findingType);
}
