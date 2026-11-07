package com.aegisx.settlement.service;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.Settlement;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.repository.SettlementRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.aegisx.audit.service.AuditService;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.entity.AuditEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final UserRepository userRepository;
    private final SettlementProcessorService settlementProcessorService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final RazorpayXClient razorpayXClient;
    private final EntityManager entityManager;

    /**
     * Initiates a new settlement for a transaction.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public SettlementResponse initiateSettlement(SettlementRequest request, String initiatedBy) {
        if (request == null) {
            throw new IllegalArgumentException("Settlement request is required");
        }
        log.info("Initiating settlement for transaction: {}", request.getTransactionId());

        if (request.getTransactionId() == null) {
            request.validate();
        }

        // Check if transaction exists and is eligible for settlement
        Transaction transaction = transactionRepository.findById(request.getTransactionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

        if (request.getSettlementAmount() != null
                && request.getSettlementAmount().compareTo(BigDecimal.ZERO) <= 0) {
            auditRejectedAmount(transaction.getId(), "Settlement amount must be positive");
            throw new IllegalArgumentException("Settlement amount must be positive");
        }

        request.validate();

        if (!isTransactionEligibleForSettlement(transaction)) {
            throw new IllegalStateException("Transaction is not eligible for settlement");
        }

        if (request.getSettlementAmount().compareTo(transaction.getAmount()) > 0) {
            auditRejectedAmount(transaction.getId(),
                    "Settlement amount exceeds the approved transaction amount");
            throw new IllegalArgumentException(
                    "Settlement amount cannot exceed the approved transaction amount");
        }

        if (!request.getPaymentProcessor().supportsNewSettlements()) {
            throw new IllegalArgumentException("No settlement integration is available for this processor");
        }

        if (request.getPaymentProcessor() == PaymentProcessor.RAZORPAYX
                && (!"INR".equals(transaction.getCurrency())
                || !"INR".equals(request.getSettlementCurrency())
                || request.getSettlementAmount().compareTo(transaction.getAmount()) > 0)) {
            throw new IllegalArgumentException(
                    "RazorpayX settlement must be INR and cannot exceed the transaction amount");
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
        if (request.getPaymentProcessor() == PaymentProcessor.RAZORPAYX) {
            saved.setProcessorDetails(null, razorpayXClient.createBeneficiaryFundAccount(saved, request));
            saved = settlementRepository.save(saved);
        }
        
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
    @Transactional(noRollbackFor = SettlementProcessingException.class)
    public SettlementResponse processSettlement(UUID settlementId) {
        log.info("Processing settlement: {}", settlementId);
        
        Settlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found"));
        
        if (settlement.getStatus() != SettlementStatus.INITIATED) {
            throw new IllegalStateException("Settlement is not in INITIATED status");
        }
        Transaction transaction = settlement.getTransaction();
        entityManager.refresh(transaction, LockModeType.PESSIMISTIC_WRITE);
        if (!isTransactionEligibleForSettlement(transaction)) {
            throw new IllegalStateException("Transaction is no longer approved for settlement");
        }
        
        try {
            // Update status to processing
            settlement.updateStatus(SettlementStatus.PROCESSING, null);
            settlementRepository.save(settlement);
            
            // Submit to payment processor
            SettlementProcessorResult result = settlementProcessorService.submitSettlement(settlement);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getErrorMessage() == null
                        ? "Settlement provider rejected the request" : result.getErrorMessage());
            }

            SettlementStatus initialStatus = result.getInitialStatus() == null
                    ? SettlementStatus.PENDING : result.getInitialStatus();
            if (settlement.getPaymentProcessor() == PaymentProcessor.SIMULATED) {
                if (initialStatus != SettlementStatus.PENDING || result.getProcessorTransactionId() != null) {
                    throw new IllegalStateException("Simulated settlement returned provider execution evidence");
                }
            } else if (settlement.getPaymentProcessor() != PaymentProcessor.RAZORPAYX
                    || result.getProcessorTransactionId() == null
                    || result.getProcessorTransactionId().isBlank()) {
                throw new IllegalStateException("Settlement response lacks real provider evidence");
            }

            // Update settlement with processor details
            settlement.setProcessorDetails(result.getProcessorTransactionId(), result.getMetadata());
            if (!isValidStatusTransition(settlement.getStatus(), initialStatus)) {
                throw new IllegalStateException("Settlement provider returned an unsupported status");
            }
            settlement.updateStatus(initialStatus, null);
            
            if (result.getExpectedCompletion() != null) {
                settlement.setExpectedCompletion(result.getExpectedCompletion());
            }
            
            Settlement updated = settlementRepository.save(settlement);
            
            AuditEventType eventType = initialStatus == SettlementStatus.SUBMITTED
                    ? AuditEventType.SETTLEMENT_SUBMITTED : AuditEventType.SETTLEMENT_STATUS_UPDATED;
            String description = settlement.getPaymentProcessor() == PaymentProcessor.SIMULATED
                    ? "Settlement marked SIMULATED; no money moved"
                    : "Provider returned settlement status " + initialStatus;
            auditService.logEvent(eventType, AuditModule.SETTLEMENT,
                    description, settlement.getTransaction().getId());

            log.info("Settlement {} recorded with status {}", settlement.getSettlementReference(), initialStatus);
            
            return SettlementResponse.fromEntity(updated);
            
        } catch (Exception e) {
            log.error("Failed to process settlement {}: {}", settlementId, e.getMessage());
            
            settlement.updateStatus(SettlementStatus.FAILED, e.getMessage());
            settlementRepository.save(settlement);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_FAILED, AuditModule.SETTLEMENT,
                    "Settlement processing failed: " + e.getMessage(), settlement.getTransaction().getId());
            
            throw new SettlementProcessingException("Settlement processing failed", e);
        }
    }

    @Transactional
    public String applyRazorpayXPayoutWebhook(String payoutId,
                                               String settlementReference,
                                               BigDecimal amountMinor,
                                               String currency,
                                               String providerStatus,
                                               String reason,
                                               String utr) {
        Settlement settlement = settlementRepository.findBySettlementReference(settlementReference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown RazorpayX settlement reference"));
        if (settlement.getPaymentProcessor() != PaymentProcessor.RAZORPAYX
                || payoutId == null || payoutId.isBlank()
                || (settlement.getProcessorTransactionId() != null
                && !settlement.getProcessorTransactionId().equals(payoutId))
                || amountMinor == null
                || amountMinor.compareTo(settlement.getSettlementAmount().movePointRight(2)) != 0
                || !"INR".equals(currency)) {
            throw new IllegalArgumentException("RazorpayX payout webhook does not match the stored settlement");
        }

        SettlementStatus newStatus = switch (providerStatus == null ? ""
                : providerStatus.toLowerCase(java.util.Locale.ROOT)) {
            case "queued", "pending", "processing", "initiated" -> SettlementStatus.PENDING;
            case "processed" -> SettlementStatus.COMPLETED;
            case "failed", "reversed" -> SettlementStatus.FAILED;
            case "rejected" -> SettlementStatus.REJECTED;
            case "cancelled" -> SettlementStatus.CANCELLED;
            default -> SettlementStatus.MANUAL_REVIEW;
        };
        if ((settlement.getStatus() == SettlementStatus.COMPLETED
                && newStatus == SettlementStatus.PENDING)
                || (settlement.getStatus() == SettlementStatus.RECONCILED
                && (newStatus == SettlementStatus.PENDING || newStatus == SettlementStatus.COMPLETED))) {
            return settlement.getTransaction().getReferenceNumber();
        }
        if (settlement.getStatus() == SettlementStatus.RECONCILED && newStatus == SettlementStatus.FAILED) {
            newStatus = SettlementStatus.RECONCILIATION_FAILED;
        }
        if (settlement.getStatus() != newStatus) {
            boolean providerOutcomeCorrection = (settlement.getProcessorTransactionId() == null
                    && (settlement.getStatus() == SettlementStatus.INITIATED
                    || settlement.getStatus() == SettlementStatus.PROCESSING
                    || settlement.getStatus() == SettlementStatus.FAILED
                    || settlement.getStatus() == SettlementStatus.REJECTED
                    || settlement.getStatus() == SettlementStatus.MANUAL_REVIEW))
                    || settlement.getStatus() == SettlementStatus.FAILED
                    || settlement.getStatus() == SettlementStatus.REJECTED
                    || settlement.getStatus() == SettlementStatus.MANUAL_REVIEW
                    || (settlement.getStatus() == SettlementStatus.COMPLETED
                    && newStatus == SettlementStatus.FAILED)
                    || (settlement.getStatus() == SettlementStatus.RECONCILED
                    && newStatus == SettlementStatus.RECONCILIATION_FAILED);
            if (!isValidStatusTransition(settlement.getStatus(), newStatus) && !providerOutcomeCorrection) {
                throw new IllegalStateException("Invalid RazorpayX payout status transition");
            }
            settlement.updateStatus(newStatus, reason);
        }
        com.fasterxml.jackson.databind.node.ObjectNode metadata = settlement.getProcessorMetadata()
                instanceof com.fasterxml.jackson.databind.node.ObjectNode existing
                ? existing.deepCopy() : objectMapper.createObjectNode();
        metadata.put("payoutId", payoutId);
        metadata.put("payoutStatus", providerStatus);
        if (utr != null && !utr.isBlank()) {
            metadata.put("utr", utr);
        }
        settlement.setProcessorDetails(payoutId, metadata);
        settlementRepository.save(settlement);
        auditService.logEvent(mapStatusToAuditEvent(newStatus), AuditModule.SETTLEMENT,
                "RazorpayX payout status updated to " + newStatus,
                settlement.getTransaction().getId());
        return settlement.getTransaction().getReferenceNumber();
    }

    /**
     * Updates settlement status based on processor callback.
     */
    public SettlementResponse updateSettlementStatus(String settlementReference, 
                                                    SettlementStatus newStatus, 
                                                    String reason,
                                                    JsonNode processorData) {
        log.info("Updating settlement status: {} to {}", settlementReference, newStatus);

        if (newStatus == SettlementStatus.SUBMITTED || newStatus == SettlementStatus.RECONCILED) {
            throw new IllegalStateException("Settlement status requires real provider evidence");
        }
        
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

    @Transactional(readOnly = true)
    public SettlementResponse getSettlement(UUID settlementId, Authentication authentication) {
        User requester = requireRequester(authentication);
        Settlement settlement = isMerchant(authentication)
                ? settlementRepository.findByIdAndMerchantId(settlementId, requester.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Settlement not found"))
                : getSettlementById(settlementId);
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

    @Transactional(readOnly = true)
    public SettlementResponse getSettlementByReference(String reference, Authentication authentication) {
        User requester = requireRequester(authentication);
        Settlement settlement = isMerchant(authentication)
                ? settlementRepository.findByReferenceAndMerchantId(reference, requester.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Settlement not found"))
                : settlementRepository.findBySettlementReference(reference)
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

    @Transactional(readOnly = true)
    public List<SettlementResponse> getTransactionSettlements(UUID transactionId, Authentication authentication) {
        User requester = requireRequester(authentication);
        if (isMerchant(authentication)
                && transactionRepository.findByIdAndMerchantId(transactionId, requester.getId()).isEmpty()) {
            throw new IllegalArgumentException("Transaction not found");
        }
        return getTransactionSettlements(transactionId);
    }

    private User requireRequester(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalArgumentException("Settlement not found");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found"));
    }

    private boolean isMerchant(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_MERCHANT".equals(authority.getAuthority()));
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
        if (settlement.getPaymentProcessor() == PaymentProcessor.RAZORPAYX
                && settlement.getProcessorTransactionId() != null
                && !settlement.getProcessorTransactionId().isBlank()) {
            throw new IllegalStateException(
                    "RazorpayX has assigned a payout ID; reconcile the existing payout before retrying");
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
        
        if (settlement.getStatus() != SettlementStatus.INITIATED) {
            throw new IllegalStateException(
                    "Only an unsubmitted settlement can be cancelled; provider-submitted payouts require provider confirmation");
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
            if (settlement.getPaymentProcessor() != PaymentProcessor.RAZORPAYX) {
                settlement.updateStatus(SettlementStatus.MANUAL_REVIEW,
                        "No live provider reconciliation is configured for this processor");
                settlementRepository.save(settlement);
                continue;
            }
            try {
                settlement.updateStatus(SettlementStatus.RECONCILING, null);
                settlementRepository.save(settlement);

                JsonNode providerPayout = settlementProcessorService.querySettlementStatus(settlement);
                long expectedAmount = settlement.getSettlementAmount().movePointRight(2).longValueExact();
                if (!settlement.getProcessorTransactionId().equals(providerPayout.path("id").asText())
                        || !settlement.getSettlementReference().equals(providerPayout.path("reference_id").asText())
                        || providerPayout.path("amount").asLong(-1) != expectedAmount
                        || !"INR".equals(providerPayout.path("currency").asText())) {
                    throw new IllegalStateException("Provider payout does not match stored settlement");
                }

                String providerStatus = providerPayout.path("status").asText();
                com.fasterxml.jackson.databind.node.ObjectNode reconciliationData = objectMapper.createObjectNode();
                reconciliationData.put("id", providerPayout.path("id").asText());
                reconciliationData.put("reference_id", providerPayout.path("reference_id").asText());
                reconciliationData.put("amount", expectedAmount);
                reconciliationData.put("currency", providerPayout.path("currency").asText());
                reconciliationData.put("status", providerStatus);
                if (providerPayout.hasNonNull("utr")) {
                    reconciliationData.put("utr", providerPayout.path("utr").asText());
                }
                settlement.setProcessorDetails(settlement.getProcessorTransactionId(), reconciliationData);
                if ("processed".equalsIgnoreCase(providerStatus)) {
                    settlement.updateStatus(SettlementStatus.RECONCILED, null);
                } else {
                    settlement.updateStatus(SettlementStatus.RECONCILIATION_FAILED,
                            "Provider payout status is " + providerStatus);
                }
                settlementRepository.save(settlement);
                log.info("RazorpayX payout reconciliation completed for {}", settlement.getSettlementReference());
                
            } catch (Exception e) {
                log.error("Reconciliation failed for settlement {}: {}",
                        settlement.getSettlementReference(), e.getMessage());
                
                settlement.updateStatus(SettlementStatus.RECONCILIATION_FAILED, e.getMessage());
                settlementRepository.save(settlement);
            }
        }
    }

    // Helper methods

    private void auditRejectedAmount(UUID transactionId, String reason) {
        auditService.logEvent(AuditEventType.SETTLEMENT_FAILED, AuditModule.SETTLEMENT,
                "Settlement request rejected: " + reason, transactionId);
    }
    
    private Settlement getSettlementById(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement not found"));
    }

    private boolean isTransactionEligibleForSettlement(Transaction transaction) {
        // APPROVED is the legacy status corresponding to the policy engine's ALLOWED decision.
        return transaction.getStatus() == TransactionStatus.APPROVED;
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
        private SettlementStatus initialStatus;
        private boolean success;
        private String errorMessage;
    }

    public static class SettlementProcessingException extends RuntimeException {
        public SettlementProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
