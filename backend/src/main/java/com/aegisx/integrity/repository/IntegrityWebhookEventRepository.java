package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.WebhookProcessingState;
import com.aegisx.integrity.entity.IntegrityWebhookEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IntegrityWebhookEventRepository extends JpaRepository<IntegrityWebhookEvent, UUID> {

    /**
     * Looks an event up within one workspace.
     *
     * <p>The workspace is part of the identity: the same provider event id can legitimately exist for
     * two workspaces bound to one provider account. A lookup without the workspace would classify the
     * second workspace's first delivery as a duplicate of the first workspace's row.</p>
     */
    Optional<IntegrityWebhookEvent> findByWorkspaceIdAndProviderAndProviderEventId(
            UUID workspaceId, String provider, String providerEventId);

    List<IntegrityWebhookEvent> findAllByWorkspaceIdOrderByReceivedAtDesc(UUID workspaceId);

    Page<IntegrityWebhookEvent> findByWorkspaceIdOrderByReceivedAtDesc(UUID workspaceId, Pageable pageable);

    long countByWorkspaceIdAndProcessingState(UUID workspaceId, WebhookProcessingState processingState);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndProviderResourceIdAndProcessingState(UUID workspaceId, String providerResourceId, WebhookProcessingState state);
}
