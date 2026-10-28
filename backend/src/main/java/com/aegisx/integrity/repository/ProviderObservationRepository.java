package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.ResourceType;
import com.aegisx.integrity.entity.ProviderObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProviderObservationRepository extends JpaRepository<ProviderObservation, UUID> {

    /** Highest-ranked, most recent observation of an order. */
    Optional<ProviderObservation> findTopByResourceTypeAndOrderIdOrderByEvidenceRankDescObservedAtDesc(
            ResourceType resourceType, UUID orderId);

    Optional<ProviderObservation> findTopByResourceTypeAndPaymentIdOrderByEvidenceRankDescObservedAtDesc(
            ResourceType resourceType, UUID paymentId);

    Optional<ProviderObservation> findTopByResourceTypeAndRefundIdOrderByEvidenceRankDescObservedAtDesc(
            ResourceType resourceType, UUID refundId);

    Optional<ProviderObservation> findTopByResourceTypeAndTransferIdOrderByEvidenceRankDescObservedAtDesc(
            ResourceType resourceType, UUID transferId);

    List<ProviderObservation> findByWorkspaceIdAndResourceTypeOrderByObservedAtDesc(UUID workspaceId, ResourceType resourceType);

    List<ProviderObservation> findAllByWorkspaceIdOrderByObservedAtDesc(UUID workspaceId);

    long countByWorkspaceId(UUID workspaceId);
}
