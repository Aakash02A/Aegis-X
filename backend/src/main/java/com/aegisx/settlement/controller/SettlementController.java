package com.aegisx.settlement.controller;

import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.dto.SettlementResponse;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.entity.SettlementStatus;
import com.aegisx.settlement.service.SettlementService;
import com.aegisx.settlement.service.SettlementProcessorService;
import com.aegisx.common.dto.ApiResponse;
import com.aegisx.audit.service.AuditService;
import com.aegisx.audit.entity.AuditModule;
import com.aegisx.audit.entity.AuditEventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Settlement management.
 * Implements SRS Section 9.2 - Settlement Integration Module.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
@Tag(name = "Settlements", description = "Settlement processing and management")
public class SettlementController {

    private final SettlementService settlementService;
    private final SettlementProcessorService settlementProcessorService;
    private final AuditService auditService;

    /**
     * Initiates a new settlement for a transaction.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR')")
    @Operation(summary = "Initiate settlement", 
               description = "Creates a new settlement request for an approved transaction")
    public ResponseEntity<ApiResponse<SettlementResponse>> initiateSettlement(
            @Valid @RequestBody SettlementRequest request,
            Authentication authentication) {
        
        log.info("Initiating settlement for transaction: {}", request.getTransactionId());
        
        try {
            String initiatedBy = authentication.getName();
            SettlementResponse response = settlementService.initiateSettlement(request, initiatedBy);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Settlement initiated successfully"));
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid settlement request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid request: " + e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Settlement not allowed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Settlement not allowed: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to initiate settlement", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to initiate settlement"));
        }
    }

    /**
     * Processes a settlement by submitting to payment processor.
     */
    @PostMapping("/{settlementId}/process")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'SYSTEM')")
    @Operation(summary = "Process settlement", 
               description = "Submits settlement to payment processor for execution")
    public ResponseEntity<ApiResponse<SettlementResponse>> processSettlement(
            @Parameter(description = "Settlement ID") @PathVariable UUID settlementId) {
        
        log.info("Processing settlement: {}", settlementId);
        
        try {
            SettlementResponse response = settlementService.processSettlement(settlementId);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Settlement processed successfully"));
            
        } catch (IllegalStateException e) {
            log.warn("Cannot process settlement: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Cannot process settlement: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process settlement: {}", settlementId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to process settlement"));
        }
    }

    /**
     * Gets settlement details by ID.
     */
    @GetMapping("/{settlementId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR', 'MERCHANT')")
    @Operation(summary = "Get settlement", 
               description = "Retrieves settlement details by ID")
    public ResponseEntity<ApiResponse<SettlementResponse>> getSettlement(
            @Parameter(description = "Settlement ID") @PathVariable UUID settlementId,
            Authentication authentication) {
        
        log.debug("Getting settlement: {}", settlementId);
        
        try {
            SettlementResponse response = settlementService.getSettlement(settlementId, authentication);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlement: " + settlementId);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Settlement retrieved"));
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get settlement: {}", settlementId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlement"));
        }
    }

    /**
     * Gets settlement by reference number.
     */
    @GetMapping("/reference/{reference}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR', 'MERCHANT')")
    @Operation(summary = "Get settlement by reference", 
               description = "Retrieves settlement details by reference number")
    public ResponseEntity<ApiResponse<SettlementResponse>> getSettlementByReference(
            @Parameter(description = "Settlement reference") @PathVariable String reference,
            Authentication authentication) {
        
        log.debug("Getting settlement by reference: {}", reference);
        
        try {
            SettlementResponse response = settlementService.getSettlementByReference(
                    reference, authentication);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlement by reference: " + reference);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Settlement retrieved"));
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get settlement by reference: {}", reference, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlement"));
        }
    }

    /**
     * Gets all settlements for a transaction.
     */
    @GetMapping("/transaction/{transactionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR', 'MERCHANT')")
    @Operation(summary = "Get transaction settlements", 
               description = "Retrieves all settlements for a specific transaction")
    public ResponseEntity<ApiResponse<List<SettlementResponse>>> getTransactionSettlements(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId,
            Authentication authentication) {
        
        log.debug("Getting settlements for transaction: {}", transactionId);
        
        try {
            List<SettlementResponse> settlements = settlementService.getTransactionSettlements(
                    transactionId, authentication);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlements for transaction: " + transactionId);
            
            return ResponseEntity.ok(ApiResponse.success(settlements, 
                    "Retrieved " + settlements.size() + " settlements"));
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get settlements for transaction: {}", transactionId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlements"));
        }
    }

    /**
     * Gets settlements by status with pagination.
     */
    @GetMapping("/status/{status}")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'SETTLEMENT_OPERATOR')")
    @Operation(summary = "Get settlements by status", 
               description = "Retrieves settlements filtered by status with pagination")
    public ResponseEntity<ApiResponse<Page<SettlementResponse>>> getSettlementsByStatus(
            @Parameter(description = "Settlement status") @PathVariable SettlementStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "initiatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        log.debug("Getting settlements by status: {} (page: {}, size: {})", status, page, size);
        
        try {
            Sort.Direction direction = sortDir.equalsIgnoreCase("asc") ? 
                    Sort.Direction.ASC : Sort.Direction.DESC;
            Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
            
            Page<SettlementResponse> settlements = settlementService.getSettlementsByStatus(status, pageable);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlements by status: " + status);
            
            return ResponseEntity.ok(ApiResponse.success(settlements, "Settlements retrieved"));
            
        } catch (Exception e) {
            log.error("Failed to get settlements by status: {}", status, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlements"));
        }
    }

    /**
     * Gets settlements requiring retry.
     */
    @GetMapping("/retry/pending")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'SYSTEM')")
    @Operation(summary = "Get settlements requiring retry", 
               description = "Retrieves settlements that can be retried")
    public ResponseEntity<ApiResponse<List<SettlementResponse>>> getSettlementsRequiringRetry() {
        
        log.debug("Getting settlements requiring retry");
        
        try {
            List<SettlementResponse> settlements = settlementService.getSettlementsRequiringRetry();
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlements requiring retry");
            
            return ResponseEntity.ok(ApiResponse.success(settlements, 
                    "Retrieved " + settlements.size() + " settlements requiring retry"));
            
        } catch (Exception e) {
            log.error("Failed to get settlements requiring retry", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlements"));
        }
    }

    /**
     * Gets overdue settlements.
     */
    @GetMapping("/overdue")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'ADMIN')")
    @Operation(summary = "Get overdue settlements", 
               description = "Retrieves settlements that are past their expected completion time")
    public ResponseEntity<ApiResponse<List<SettlementResponse>>> getOverdueSettlements() {
        
        log.debug("Getting overdue settlements");
        
        try {
            List<SettlementResponse> settlements = settlementService.getOverdueSettlements();
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed overdue settlements");
            
            return ResponseEntity.ok(ApiResponse.success(settlements, 
                    "Retrieved " + settlements.size() + " overdue settlements"));
            
        } catch (Exception e) {
            log.error("Failed to get overdue settlements", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve overdue settlements"));
        }
    }

    /**
     * Gets settlements requiring manual intervention.
     */
    @GetMapping("/manual-intervention")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Get settlements requiring manual intervention", 
               description = "Retrieves settlements that need manual review or action")
    public ResponseEntity<ApiResponse<List<SettlementResponse>>> getSettlementsRequiringManualIntervention() {
        
        log.debug("Getting settlements requiring manual intervention");
        
        try {
            List<SettlementResponse> settlements = settlementService.getSettlementsRequiringManualIntervention();
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlements requiring manual intervention");
            
            return ResponseEntity.ok(ApiResponse.success(settlements, 
                    "Retrieved " + settlements.size() + " settlements requiring intervention"));
            
        } catch (Exception e) {
            log.error("Failed to get settlements requiring manual intervention", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlements"));
        }
    }

    /**
     * Retries a failed settlement.
     */
    @PostMapping("/{settlementId}/retry")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'ADMIN')")
    @Operation(summary = "Retry settlement", 
               description = "Retries a failed settlement")
    public ResponseEntity<ApiResponse<SettlementResponse>> retrySettlement(
            @Parameter(description = "Settlement ID") @PathVariable UUID settlementId,
            @RequestParam(required = false) String reason) {
        
        log.info("Retrying settlement: {}", settlementId);
        
        try {
            String retryReason = reason != null ? reason : "Manual retry requested";
            SettlementResponse response = settlementService.retrySettlement(settlementId, retryReason);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Settlement retry initiated"));
            
        } catch (IllegalStateException e) {
            log.warn("Cannot retry settlement: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Cannot retry settlement: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to retry settlement: {}", settlementId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retry settlement"));
        }
    }

    /**
     * Cancels a pending settlement.
     */
    @PostMapping("/{settlementId}/cancel")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'ADMIN')")
    @Operation(summary = "Cancel settlement", 
               description = "Cancels a pending settlement")
    public ResponseEntity<ApiResponse<SettlementResponse>> cancelSettlement(
            @Parameter(description = "Settlement ID") @PathVariable UUID settlementId,
            @RequestParam String reason) {
        
        log.info("Cancelling settlement: {}", settlementId);
        
        try {
            SettlementResponse response = settlementService.cancelSettlement(settlementId, reason);
            
            return ResponseEntity.ok(ApiResponse.success(response, "Settlement cancelled"));
            
        } catch (IllegalStateException e) {
            log.warn("Cannot cancel settlement: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Cannot cancel settlement: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to cancel settlement: {}", settlementId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to cancel settlement"));
        }
    }

    /**
     * Gets settlement statistics by processor.
     */
    @GetMapping("/statistics/processors")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'ADMIN')")
    @Operation(summary = "Get settlement statistics by processor", 
               description = "Provides settlement statistics grouped by payment processor")
    public ResponseEntity<ApiResponse<Map<PaymentProcessor, SettlementService.SettlementStats>>> getSettlementStatsByProcessor(
            @Parameter(description = "Start date (ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date (ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        
        log.debug("Getting settlement statistics from {} to {}", startDate, endDate);
        
        try {
            if (startDate.isAfter(endDate)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Start date must be before end date"));
            }
            
            Map<PaymentProcessor, SettlementService.SettlementStats> stats = 
                    settlementService.getSettlementStatsByProcessor(startDate, endDate);
            
            auditService.logEvent(AuditEventType.SETTLEMENT_VIEW, AuditModule.SETTLEMENT,
                    "Viewed settlement statistics by processor");
            
            return ResponseEntity.ok(ApiResponse.success(stats, "Settlement statistics retrieved"));
            
        } catch (Exception e) {
            log.error("Failed to get settlement statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve statistics"));
        }
    }

    /**
     * Processes settlement reconciliation.
     */
    @PostMapping("/reconciliation/process")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'ADMIN')")
    @Operation(summary = "Process reconciliation", 
               description = "Processes settlement reconciliation with bank records")
    public ResponseEntity<ApiResponse<String>> processReconciliation() {
        
        log.info("Processing settlement reconciliation");
        
        try {
            settlementService.processReconciliation();
            
            auditService.logEvent(AuditEventType.SETTLEMENT_STATUS_UPDATED, AuditModule.SETTLEMENT,
                    "Settlement reconciliation checks processed");
            return ResponseEntity.ok(ApiResponse.success("Reconciliation checks completed",
                    "Settlement reconciliation checks processed"));
            
        } catch (Exception e) {
            log.error("Failed to process reconciliation", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to process reconciliation"));
        }
    }

    /**
     * Gets available payment processors.
     */
    @GetMapping("/processors")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Get payment processors", 
               description = "Retrieves list of available payment processors")
    public ResponseEntity<ApiResponse<PaymentProcessor[]>> getPaymentProcessors() {
        
        log.debug("Getting available payment processors");
        
        try {
            PaymentProcessor[] processors = settlementProcessorService.getConfiguredProcessors();
            
            return ResponseEntity.ok(ApiResponse.success(processors, 
                    "Retrieved " + processors.length + " payment processors"));
            
        } catch (Exception e) {
            log.error("Failed to get payment processors", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve payment processors"));
        }
    }

    /**
     * Gets settlement statuses.
     */
    @GetMapping("/statuses")
    @PreAuthorize("hasAnyRole('SETTLEMENT_OPERATOR', 'COMPLIANCE_OFFICER')")
    @Operation(summary = "Get settlement statuses", 
               description = "Retrieves list of settlement statuses")
    public ResponseEntity<ApiResponse<SettlementStatus[]>> getSettlementStatuses() {
        
        log.debug("Getting settlement statuses");
        
        try {
            SettlementStatus[] statuses = SettlementStatus.values();
            
            return ResponseEntity.ok(ApiResponse.success(statuses, 
                    "Retrieved " + statuses.length + " settlement statuses"));
            
        } catch (Exception e) {
            log.error("Failed to get settlement statuses", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve settlement statuses"));
        }
    }
}