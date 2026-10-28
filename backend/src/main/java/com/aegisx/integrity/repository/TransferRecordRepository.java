package com.aegisx.integrity.repository;

import com.aegisx.integrity.entity.TransferRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRecordRepository extends JpaRepository<TransferRecord, UUID> {

    List<TransferRecord> findByPaymentId(UUID paymentId);

    List<TransferRecord> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<TransferRecord> findByWorkspaceIdAndProviderTransferId(UUID workspaceId, String providerTransferId);

    long countByWorkspaceId(UUID workspaceId);
}
