package com.aegisx.policy.controller;

import com.aegisx.policy.dto.PolicyViolationResponse;
import com.aegisx.policy.dto.ViolationAnalysisResponse;
import com.aegisx.policy.dto.ViolationReportRequest;
import com.aegisx.policy.entity.ViolationSeverity;
import com.aegisx.policy.service.PolicyViolationService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Policy Violation management.
 * Implements SRS Section 8.3 - Policy Violations Storage and Management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/policy-violations")
@RequiredArgsConstructor
@Tag(name = "Policy Violations", description = "Policy violation management and analysis")
public class PolicyViolationController {

    private final PolicyViolationService policyViolationService;
    private final AuditService auditService;

    /**
     * Gets all violations for a specific transaction.
     * Used by compliance officers and merchants to view violations.
     */
    @GetMapping("/transaction/{transactionId}")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'MERCHANT', 'ADMIN')")
    @Operation(summary = "Get violations for transaction", 
               description = "Retrieves all policy violations for a specific transaction")
    public ResponseEntity<ApiResponse<List<PolicyViolationResponse>>> getTransactionViolations(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.info("Getting violations for transaction: {}", transactionId);
        
        try {
            List<PolicyViolationResponse> violations = policyViolationService.getViolationsForTransaction(transactionId);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_VIEW, AuditModule.POLICY_ENGINE,
                    "Viewed violations for transaction: " + transactionId, transactionId);
            
            return ResponseEntity.ok(ApiResponse.success(violations, 
                    "Retrieved " + violations.size() + " violations"));
                    
        } catch (Exception e) {
            log.error("Error getting violations for transaction: {}", transactionId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve violations"));
        }
    }

    /**
     * Analyzes violations for a transaction and provides risk assessment.
     */
    @GetMapping("/transaction/{transactionId}/analysis")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Analyze transaction violations", 
               description = "Provides comprehensive violation analysis and risk assessment")
    public ResponseEntity<ApiResponse<ViolationAnalysisResponse>> analyzeTransactionViolations(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.info("Analyzing violations for transaction: {}", transactionId);
        
        try {
            ViolationAnalysisResponse analysis = policyViolationService.analyzeTransactionViolations(transactionId);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_ANALYSIS, AuditModule.POLICY_ENGINE,
                    "Analyzed violations for transaction: " + transactionId, transactionId);
            
            return ResponseEntity.ok(ApiResponse.success(analysis, "Violation analysis completed"));
                    
        } catch (Exception e) {
            log.error("Error analyzing violations for transaction: {}", transactionId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to analyze violations"));
        }
    }

    /**
     * Gets violations by severity level with pagination.
     */
    @GetMapping("/severity/{severity}")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Get violations by severity", 
               description = "Retrieves violations filtered by severity level")
    public ResponseEntity<ApiResponse<Page<PolicyViolationResponse>>> getViolationsBySeverity(
            @Parameter(description = "Violation severity") @PathVariable ViolationSeverity severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        log.info("Getting violations by severity: {} (page: {}, size: {})", severity, page, size);
        
        try {
            Pageable pageable = PageRequest.of(page, size);
            Page<PolicyViolationResponse> violations = policyViolationService.getViolationsBySeverity(severity, pageable);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_VIEW, AuditModule.POLICY_ENGINE,
                    "Viewed violations by severity: " + severity);
            
            return ResponseEntity.ok(ApiResponse.success(violations, 
                    "Retrieved violations by severity"));
                    
        } catch (Exception e) {
            log.error("Error getting violations by severity: {}", severity, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve violations"));
        }
    }

    /**
     * Gets violations requiring immediate attention (Critical and High severity).
     */
    @GetMapping("/attention")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Get violations requiring attention", 
               description = "Retrieves violations that require immediate compliance officer attention")
    public ResponseEntity<ApiResponse<List<PolicyViolationResponse>>> getViolationsRequiringAttention() {
        
        log.info("Getting violations requiring attention");
        
        try {
            List<PolicyViolationResponse> violations = policyViolationService.getViolationsRequiringAttention();
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_VIEW, AuditModule.POLICY_ENGINE,
                    "Viewed violations requiring attention");
            
            return ResponseEntity.ok(ApiResponse.success(violations, 
                    "Retrieved " + violations.size() + " violations requiring attention"));
                    
        } catch (Exception e) {
            log.error("Error getting violations requiring attention", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve violations"));
        }
    }

    /**
     * Gets violation statistics by rule.
     */
    @GetMapping("/statistics/rules")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Get violation statistics by rule", 
               description = "Provides statistics on violations grouped by policy rule")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getViolationStatsByRule() {
        
        log.info("Getting violation statistics by rule");
        
        try {
            Map<String, Long> stats = policyViolationService.getViolationStatsByRule();
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_VIEW, AuditModule.POLICY_ENGINE,
                    "Viewed violation statistics by rule");
            
            return ResponseEntity.ok(ApiResponse.success(stats, "Retrieved violation statistics"));
                    
        } catch (Exception e) {
            log.error("Error getting violation statistics by rule", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve statistics"));
        }
    }

    /**
     * Gets violations for a specific rule.
     */
    @GetMapping("/rule/{ruleId}")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Get violations by rule", 
               description = "Retrieves all violations for a specific policy rule")
    public ResponseEntity<ApiResponse<List<PolicyViolationResponse>>> getViolationsByRule(
            @Parameter(description = "Rule ID") @PathVariable String ruleId) {
        
        log.info("Getting violations for rule: {}", ruleId);
        
        try {
            List<PolicyViolationResponse> violations = policyViolationService.getViolationsByRule(ruleId);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_VIEW, AuditModule.POLICY_ENGINE,
                    "Viewed violations for rule: " + ruleId);
            
            return ResponseEntity.ok(ApiResponse.success(violations, 
                    "Retrieved " + violations.size() + " violations for rule"));
                    
        } catch (Exception e) {
            log.error("Error getting violations for rule: {}", ruleId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve violations"));
        }
    }

    /**
     * Generates a violation report based on specified criteria.
     */
    @PostMapping("/report")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Generate violation report", 
               description = "Generates comprehensive violation report based on criteria")
    public ResponseEntity<ApiResponse<PolicyViolationService.ViolationReportResponse>> generateViolationReport(
            @Valid @RequestBody ViolationReportRequest request) {
        
        log.info("Generating violation report: {}", request.getSafeReportType());
        
        try {
            PolicyViolationService.ViolationReportResponse report = 
                    policyViolationService.generateViolationReport(request);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_REPORT, AuditModule.POLICY_ENGINE,
                    "Generated violation report: " + request.getSafeReportType());
            
            return ResponseEntity.ok(ApiResponse.success(report, "Violation report generated"));
                    
        } catch (IllegalArgumentException e) {
            log.warn("Invalid report request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid report criteria: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Error generating violation report", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to generate report"));
        }
    }

    /**
     * Gets violation trends over a time period.
     */
    @GetMapping("/trends")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Get violation trends", 
               description = "Provides violation trend analysis over specified time period")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getViolationTrends(
            @Parameter(description = "Start date (ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date (ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        
        log.info("Getting violation trends from {} to {}", startDate, endDate);
        
        try {
            if (startDate.isAfter(endDate)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Start date must be before end date"));
            }
            
            Map<String, Object> trends = policyViolationService.getViolationTrends(startDate, endDate);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_VIEW, AuditModule.POLICY_ENGINE,
                    "Viewed violation trends");
            
            return ResponseEntity.ok(ApiResponse.success(trends, "Violation trends retrieved"));
                    
        } catch (Exception e) {
            log.error("Error getting violation trends", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve trends"));
        }
    }

    /**
     * Checks if a transaction has critical violations.
     */
    @GetMapping("/transaction/{transactionId}/critical")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'MERCHANT', 'ADMIN')")
    @Operation(summary = "Check for critical violations", 
               description = "Checks if transaction has critical violations requiring attention")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> hasCriticalViolations(
            @Parameter(description = "Transaction ID") @PathVariable UUID transactionId) {
        
        log.debug("Checking critical violations for transaction: {}", transactionId);
        
        try {
            boolean hasCritical = policyViolationService.hasCriticalViolations(transactionId);
            
            Map<String, Boolean> result = Map.of(
                    "hasCriticalViolations", hasCritical,
                    "requiresManualReview", hasCritical
            );
            
            return ResponseEntity.ok(ApiResponse.success(result, 
                    hasCritical ? "Critical violations detected" : "No critical violations"));
                    
        } catch (Exception e) {
            log.error("Error checking critical violations for transaction: {}", transactionId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to check violations"));
        }
    }

    /**
     * Gets a default violation report for the last 30 days.
     */
    @GetMapping("/report/default")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN')")
    @Operation(summary = "Get default violation report", 
               description = "Generates a default violation report for the last 30 days")
    public ResponseEntity<ApiResponse<PolicyViolationService.ViolationReportResponse>> getDefaultReport() {
        
        log.info("Generating default violation report");
        
        try {
            ViolationReportRequest request = ViolationReportRequest.defaultReport();
            PolicyViolationService.ViolationReportResponse report = 
                    policyViolationService.generateViolationReport(request);
            
            auditService.logEvent(AuditEventType.POLICY_VIOLATION_REPORT, AuditModule.POLICY_ENGINE,
                    "Generated default violation report");
            
            return ResponseEntity.ok(ApiResponse.success(report, "Default violation report generated"));
                    
        } catch (Exception e) {
            log.error("Error generating default violation report", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to generate default report"));
        }
    }
}