package com.aegisx.integrity.repository;

import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.entity.RefundRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRecordRepository extends JpaRepository<RefundRecord, UUID> {

    List<RefundRecord> findByPaymentId(UUID paymentId);

    List<RefundRecord> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<RefundRecord> findByWorkspaceIdAndProviderRefundId(UUID workspaceId, String providerRefundId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndState(UUID workspaceId, RefundState state);
}
