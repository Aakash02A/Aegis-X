package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findAllByWorkspaceIdOrderByReceivedAtDesc(UUID workspaceId);

    long countByWorkspaceIdAndProviderEventId(UUID workspaceId, String providerEventId);

    long countByWorkspaceIdAndFirstDeliveryFalse(UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);

    List<WebhookDelivery> findByEventId(UUID eventId);
}
