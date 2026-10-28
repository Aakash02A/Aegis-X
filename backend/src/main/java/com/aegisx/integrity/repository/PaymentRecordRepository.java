package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.entity.PaymentRecord;
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
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, UUID> {

    List<PaymentRecord> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<PaymentRecord> findByOrderId(UUID orderId);

    Optional<PaymentRecord> findByWorkspaceIdAndProviderPaymentId(UUID workspaceId, String providerPaymentId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndState(UUID workspaceId, PaymentState state);

    long countByWorkspaceIdAndStateIn(UUID workspaceId, java.util.Collection<PaymentState> states);

    List<PaymentRecord> findAllByWorkspaceIdAndSimulated(UUID workspaceId, boolean simulated);

    /** Test-fixture support only: ages a payment so staleness rules can be exercised. */
    @Modifying
    @Query(value = "UPDATE pi_payments SET first_observed_at = :observedAt, last_observed_at = :observedAt WHERE id = :id", nativeQuery = true)
    int backdate(@Param("id") UUID id, @Param("observedAt") Instant observedAt);
}
