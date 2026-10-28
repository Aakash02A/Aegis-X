package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByName(String name);

    List<Workspace> findAllByOrderByCreatedAtAsc();
}
