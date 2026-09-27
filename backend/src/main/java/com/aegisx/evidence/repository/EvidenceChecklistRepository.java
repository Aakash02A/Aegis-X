package com.aegisx.evidence.repository;

import com.aegisx.evidence.entity.EvidenceChecklist;
import com.aegisx.transaction.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Evidence Checklist entities.
 * Provides data access methods for evidence requirement tracking.
 */
@Repository
public interface EvidenceChecklistRepository extends JpaRepository<EvidenceChecklist, UUID> {

    /**
     * Finds the evidence checklist for a specific transaction.
     */
    Optional<EvidenceChecklist> findByTransaction(Transaction transaction);

    /**
     * Finds the evidence checklist by transaction ID.
     */
    @Query("SELECT ec FROM EvidenceChecklist ec WHERE ec.transaction.id = :transactionId")
    Optional<EvidenceChecklist> findByTransactionId(@Param("transactionId") UUID transactionId);

    /**
     * Finds all unsatisfied evidence checklists.
     */
    @Query("SELECT ec FROM EvidenceChecklist ec WHERE ec.isSatisfied = false")
    List<EvidenceChecklist> findUnsatisfiedChecklists();

    /**
     * Finds all checklists that are satisfied.
     */
    @Query("SELECT ec FROM EvidenceChecklist ec WHERE ec.isSatisfied = true")
    List<EvidenceChecklist> findSatisfiedChecklists();

    /**
     * Finds checklists where a specific document type is required but not uploaded.
     */
    @Query("SELECT ec FROM EvidenceChecklist ec WHERE " +
           "(:documentType = 'INVOICE' AND ec.invoiceRequired = true AND ec.invoiceUploaded = false) OR " +
           "(:documentType = 'SHIPPING_BILL' AND ec.shippingBillRequired = true AND ec.shippingBillUploaded = false) OR " +
           "(:documentType = 'PACKING_LIST' AND ec.packingListRequired = true AND ec.packingListUploaded = false) OR " +
           "(:documentType = 'CERTIFICATE_ORIGIN' AND ec.certificateOriginRequired = true AND ec.certificateOriginUploaded = false)")
    List<EvidenceChecklist> findChecklistsWithMissingDocument(@Param("documentType") String documentType);

    /**
     * Counts unsatisfied checklists.
     */
    @Query("SELECT COUNT(ec) FROM EvidenceChecklist ec WHERE ec.isSatisfied = false")
    long countUnsatisfiedChecklists();

    /**
     * Updates checklist satisfaction status in bulk.
     */
    @Modifying
    @Query("UPDATE EvidenceChecklist ec SET ec.isSatisfied = :satisfied, ec.evaluatedAt = CURRENT_TIMESTAMP " +
           "WHERE ec.transaction.id IN :transactionIds")
    int updateSatisfactionStatus(@Param("transactionIds") List<UUID> transactionIds, 
                                @Param("satisfied") boolean satisfied);

    /**
     * Finds checklists by merchant ID.
     */
    @Query("SELECT ec FROM EvidenceChecklist ec WHERE ec.transaction.merchant.id = :merchantId")
    List<EvidenceChecklist> findByMerchantId(@Param("merchantId") UUID merchantId);

    /**
     * Checks if a transaction has a satisfied evidence checklist.
     */
    @Query("SELECT CASE WHEN COUNT(ec) > 0 THEN true ELSE false END " +
           "FROM EvidenceChecklist ec WHERE ec.transaction.id = :transactionId AND ec.isSatisfied = true")
    boolean isChecklistSatisfiedForTransaction(@Param("transactionId") UUID transactionId);
}