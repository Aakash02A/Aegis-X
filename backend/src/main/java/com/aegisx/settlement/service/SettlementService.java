package com.aegisx.settlement.service;

import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.audit.service.AuditService;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.entity.AuditEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing settlements.
 * Implements settlement processing according to SRS Section 9.2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final TransactionRepository transactionRepository;
    private final SettlementProcessorService settlementProcessorService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Initiates a new settlement for a transaction.
     */
    public SettlementResponse initiateSettlement(SettlementRequest request, String initiatedBy) {
        log.info("Initiating settlement for transaction: {}", request.getTransactionId());
        
        // Validate request
        request.validate();
        
        // Check if transaction exists and is eligible for settlement
        Transaction transaction = transactionRepository.findById(request.getTransactionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        
        if (!isTransactionEligibleForSettlement(transaction)) {
            throw new IllegalStateException("Transaction is not eligible for settlement");
        }
        
        // Check if settlement already exists
        if (settlementRepository.existsByTransactionId(request.getTransactionId())) {
            throw new IllegalStateException("Settlement already exists for this transaction");
        }
        
        // Create settlement entity
        Settlement settlement = Settlement.createSettlement(
                transaction,
                request.getPaymentProcessor(),
                request.getSettlementAmount(),
                request.getSettlementCurrency(),
                initiatedBy
        );
        
        // Set additional properties
        settlement.setExchangeRate(request.getExchangeRate());
        settlement.setProcessingFees(request.getProcessingFees());
        
        // Calculate expected completion
        LocalDateTime expectedCompletion = LocalDateTime.now()
                .plusHours(request.getEstimatedSettlementHours());
        settlement.setExpectedCompletion(expectedCompletion);
        
        // Save settlement
        Settlement saved = settlementRepository.save(settlement);
        
        // Audit log
        auditService.logEvent(AuditEventType.SETTLEMENT_INITIATED, AuditModule.SETTLEMENT,
                "Settlement initiated", transaction.getId());
        
        log.info("Settlement initiated: {} for transaction: {}", 
                saved.getSettlementReference(), request.getTransactionId());
        
        return SettlementResponse.fromEntity(saved);
    }

    /**
     * Processes a settlement by submitting to payment processor.
     */
    public SettlementResponse processSettlement(UUID settlementId) {
        log.info("Processing settlement: {}", settlementId);
        
        Settlement settlement = getSettlementById(settlementId);
        
        if (settlement.getStatus() != SettlementStatus.INITIATED) {
            throw new IllegalStateException("Settlement is not in INITIATED status");
        }
        
        try {
            // Update status to processing
            settlement.updateStatus(SettlementStatus.PROCESSING, null);
            settlementRepository.save(settlement);
            
            // Submit to payment processor
            SettlementProcessorResult result = settlementProcessorService.submitSettlement(settlement);
            
            // Update settlement with processor details
            settlement.setProcessorDetails(result.getProcessorTransactionId(), result.getMetadata());
            settlement.updateStatus(SettlementStatus.SUBMITTED, null);
            
            if (result.getExpectedCompletion() != null) {
                settlement.setExpectedCompletion(result.getExpectedCompletion());
            }
            
            Settlement updated = settlementRepository.save(settlement);
            
            // Audit log
            auditService.logEvent(AuditEventType.SETTLEMENT_SUBMITTED, AuditModule.SETTLEMENT,
                    "Settlement submitted to processor", settlement.getTransaction().getId());
            
            log.info("Settlement submitted: {} with processor ID: {}", 
                    settlement.getSettlementReference(), result.getProcessorTransactionId());
            
            return SettlementResponse.fromEntity(updated);
            
        } catch (Exception e) {
            log.error("Failed to process settlement: {}", settlementId, e);
            
            settlement.updateStatus(SettlementStatus.FAILED, e.getMessage());
            settlementRepository.save(settlement);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_FAILED, AuditModule.SETTLEMENT,
                    "Settlement processing failed: " + e.getMessage(), settlement.getTransaction().getId());
            
            throw new RuntimeException("Settlement processing failed", e);
        }
    }

    /**
     * Updates settlement status based on processor callback.
     */
    public SettlementResponse updateSettlementStatus(String settlementReference, 
                                                    SettlementStatus newStatus, 
                                                    String reason,
                                                    JsonNode processorData) {
        log.info("Updating settlement status: {} to {}", settlementReference, newStatus);
        
        Settlement settlement = settlementRepository.findBySettlementReference(settlementReference)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found"));
        
        SettlementStatus oldStatus = settlement.getStatus();
        
        // Validate status transition
        if (!isValidStatusTransition(oldStatus, newStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid status transition from %s to %s", oldStatus, newStatus));
        }
        
        // Update settlement
        settlement.updateStatus(newStatus, reason);
        
        if (processorData != null) {
            settlement.setProcessorDetails(settlement.getProcessorTransactionId(), processorData);
        }
        
        Settlement updated = settlementRepository.save(settlement);
        
        // Audit log
        AuditEventType eventType = mapStatusToAuditEvent(newStatus);
        auditService.logEvent(eventType, AuditModule.SETTLEMENT,
                String.format("Settlement status updated from %s to %s", oldStatus, newStatus),
                settlement.getTransaction().getId());
        
        log.info("Settlement status updated: {} from {} to {}", 
                settlementReference, oldStatus, newStatus);
        
        return SettlementResponse.fromEntity(updated);
    }

    /**
     * Gets settlement by ID.
     */
    @Transactional(readOnly = true)
    public SettlementResponse getSettlement(UUID settlementId) {
        Settlement settlement = getSettlementById(settlementId);
        return SettlementResponse.fromEntity(settlement);
    }

    /**
     * Gets settlement by reference number.
     */
    @Transactional(readOnly = true)
    public SettlementResponse getSettlementByReference(String reference) {
        Settlement settlement = settlementRepository.findBySettlementReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found"));
        return SettlementResponse.fromEntity(settlement);
    }

    /**
     * Gets all settlements for a transaction.
     */
    @Transactional(readOnly = true)
    public List<SettlementResponse> getTransactionSettlements(UUID transactionId) {
        List<Settlement> settlements = settlementRepository.findByTransactionId(transactionId);
        return settlements.stream()
                .map(SettlementResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Gets settlements by status with pagination.
     */
    @Transactional(readOnly = true)
    public Page<SettlementResponse> getSettlementsByStatus(SettlementStatus status, Pageable pageable) {
        Page<Settlement> settlements = settlementRepository.findByStatus(status, pageable);
        return settlements.map(SettlementResponse::fromEntity);
    }

    /**
     * Gets settlements requiring retry.
     */
    @Transactional(readOnly = true)
    public List<SettlementResponse> getSettlementsRequiringRetry() {
        List<Settlement> settlements = settlementRepository.findSettlementsRequiringRetry(LocalDateTime.now());
        return settlements.stream()
                .map(SettlementResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Gets overdue settlements.
     */
    @Transactional(readOnly = true)
    public List<SettlementResponse> getOverdueSettlements() {
        List<Settlement> settlements = settlementRepository.findOverdueSettlements(LocalDateTime.now());
        return settlements.stream()
                .map(SettlementResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Gets settlements requiring manual intervention.
     */
    @Transactional(readOnly = true)
    public List<SettlementResponse> getSettlementsRequiringManualIntervention() {
        List<Settlement> settlements = settlementRepository.findSettlementsRequiringManualIntervention();
        return settlements.stream()
                .map(SettlementResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Retries a failed settlement.
     */
    public SettlementResponse retrySettlement(UUID settlementId, String reason) {
        log.info("Retrying settlement: {}", settlementId);
        
        Settlement settlement = getSettlementById(settlementId);
        
        if (!settlement.canRetry()) {
            throw new IllegalStateException("Settlement cannot be retried");
        }
        
        settlement.incrementRetry();
        settlement.updateStatus(SettlementStatus.INITIATED, reason);
        
        Settlement updated = settlementRepository.save(settlement);
        
        auditService.logEvent(AuditEventType.SETTLEMENT_RETRY, AuditModule.SETTLEMENT,
                "Settlement retry initiated: " + reason, settlement.getTransaction().getId());
        
        log.info("Settlement retry scheduled: {} (attempt {})", 
                settlement.getSettlementReference(), settlement.getRetryCount());
        
        return SettlementResponse.fromEntity(updated);
    }

    /**
     * Cancels a pending settlement.
     */
    public SettlementResponse cancelSettlement(UUID settlementId, String reason) {
        log.info("Cancelling settlement: {}", settlementId);
        
        Settlement settlement = getSettlementById(settlementId);
        
        if (settlement.isFinal()) {
            throw new IllegalStateException("Cannot cancel settlement in final status");
        }
        
        settlement.updateStatus(SettlementStatus.CANCELLED, reason);
        Settlement updated = settlementRepository.save(settlement);
        
        auditService.logEvent(AuditEventType.SETTLEMENT_CANCELLED, AuditModule.SETTLEMENT,
                "Settlement cancelled: " + reason, settlement.getTransaction().getId());
        
        log.info("Settlement cancelled: {} - {}", settlement.getSettlementReference(), reason);
        
        return SettlementResponse.fromEntity(updated);
    }

    /**
     * Gets settlement statistics by processor.
     */
    @Transactional(readOnly = true)
    public Map<PaymentProcessor, SettlementStats> getSettlementStatsByProcessor(
            LocalDateTime startDate, LocalDateTime endDate) {
        
        List<Object[]> stats = settlementRepository.getSettlementStatsByProcessor(startDate, endDate);
        List<Object[]> successRates = settlementRepository.getSettlementSuccessRateByProcessor(startDate, endDate);
        List<Object[]> durations = settlementRepository.getAverageSettlementDuration(startDate, endDate);
        
        return PaymentProcessor.getCrossBorderProcessors().length > 0 ?
                createStatsMap(stats, successRates, durations) : Map.of();
    }

    /**
     * Processes settlements requiring reconciliation.
     */
    public void processReconciliation() {
        log.info("Processing settlement reconciliation");
        
        List<Settlement> settlements = settlementRepository.findSettlementsPendingReconciliation();
        
        for (Settlement settlement : settlements) {
            try {
                settlement.updateStatus(SettlementStatus.RECONCILING, null);
                settlementRepository.save(settlement);
                
                // Perform reconciliation logic here
                // This would typically involve calling bank APIs or processing files
                
                settlement.updateStatus(SettlementStatus.RECONCILED, null);
                settlementRepository.save(settlement);
                
                log.info("Settlement reconciled: {}", settlement.getSettlementReference());
                
            } catch (Exception e) {
                log.error("Reconciliation failed for settlement: {}", 
                         settlement.getSettlementReference(), e);
                
                settlement.updateStatus(SettlementStatus.RECONCILIATION_FAILED, e.getMessage());
                settlementRepository.save(settlement);
            }
        }
    }

    // Helper methods
    
    private Settlement getSettlementById(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found"));
    }

    private boolean isTransactionEligibleForSettlement(Transaction transaction) {
        // Check if transaction is approved and ready for settlement
        return transaction.getStatus().name().equals("APPROVED") || 
               transaction.getStatus().name().equals("COMPLIANCE_APPROVED");
    }

    private boolean isValidStatusTransition(SettlementStatus from, SettlementStatus to) {
        SettlementStatus[] allowedTransitions = from.getNextPossibleStatuses();
        return java.util.Arrays.asList(allowedTransitions).contains(to);
    }

    private AuditEventType mapStatusToAuditEvent(SettlementStatus status) {
        return switch (status) {
            case COMPLETED -> AuditEventType.SETTLEMENT_COMPLETED;
            case FAILED -> AuditEventType.SETTLEMENT_FAILED;
            case CANCELLED -> AuditEventType.SETTLEMENT_CANCELLED;
            default -> AuditEventType.SETTLEMENT_STATUS_UPDATED;
        };
    }

    private Map<PaymentProcessor, SettlementStats> createStatsMap(List<Object[]> stats, 
                                                                 List<Object[]> successRates, 
                                                                 List<Object[]> durations) {
        // Implementation would combine the statistics
        return Map.of(); // Simplified for now
    }

    /**
     * Settlement statistics DTO.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class SettlementStats {
        private long totalCount;
        private java.math.BigDecimal averageAmount;
        private double successRate;
        private double averageDurationHours;
    }

    /**
     * Settlement processor result DTO.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class SettlementProcessorResult {
        private String processorTransactionId;
        private LocalDateTime expectedCompletion;
        private JsonNode metadata;
        private boolean success;
        private String errorMessage;
    }
}