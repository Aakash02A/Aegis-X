package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.entity.WorkspaceMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, UUID> {

    boolean existsByUser_IdAndWorkspace_Id(UUID userId, UUID workspaceId);

    Optional<WorkspaceMembership> findByUser_IdAndWorkspace_Id(UUID userId, UUID workspaceId);

    @Query("""
            select membership.workspace
            from WorkspaceMembership membership
            where membership.user.id = :userId
            order by membership.workspace.createdAt asc
            """)
    List<Workspace> findWorkspacesByUserId(@Param("userId") UUID userId);
}
