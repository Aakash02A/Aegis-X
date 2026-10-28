package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.PaymentOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    List<PaymentOrder> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Page<PaymentOrder> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    Optional<PaymentOrder> findByInternalReference(String internalReference);

    Optional<PaymentOrder> findByWorkspaceIdAndProviderOrderId(UUID workspaceId, String providerOrderId);

    long countByWorkspaceId(UUID workspaceId);

    /**
     * Test-fixture support only: ages an order so age-dependent rules can be exercised without
     * waiting. {@code created_at} is otherwise immutable.
     */
    @Modifying
    @Query(value = "UPDATE pi_orders SET created_at = :createdAt, last_observed_at = :createdAt WHERE id = :id", nativeQuery = true)
    int backdate(@Param("id") UUID id, @Param("createdAt") Instant createdAt);
}
